# DEC-003 — Cancellation, no-show, notice window and staff-approval triggers

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinic answer recorded
- **Owner:** Clinic
- **Approvers required:** clinic owner / authorized manager (window, fees, approval triggers); finance (if a fee moves money — cross-link DEC-004); clinical lead (if a window has a clinical consequence, e.g. a sedation or anaesthesia deposit); legal (if the notice window is a contractual term shown to the patient)
- **Blocks:** Clinic Phase 2 (`appointments` status vocabulary, **`appointment_status_history`**), Phase 5 (appointment lifecycle), Phase 6 (cancellation notices and replies), Phase 11 (attendance / no-show reporting)
- **Related:** DEC-001 (durations), DEC-002 (a cancellation frees a chair), DEC-004 (a cancellation fee is money), DEC-005 (channels and reminders), DEC-006 (AI `request_cancellation`), DEC-014 (configurable roles)

> **This document decides nothing.** `firstelement-dental-clinic-feature-kit/.../plan.md:17`: *"Do not
> invent a … clinical status list, triage protocol, **cancellation policy** …"*
> The Arabic source itself makes the status list conditional: `source ¶52` ends with
> «**تعتمد القائمة النهائية مع العميل**» — *"the final list is approved with the client."*

---

## 1. Question

What **notice window** applies to cancelling or rescheduling, are there **fees or penalties**, what
happens after **repeated no-show**, and which actions require **staff approval** before they take effect?

`source ¶305`, verbatim:

> «ما المهلة المسموحة للإلغاء؟ هل توجد رسوم أو عقوبات؟ ماذا يحدث بعد تكرار عدم الحضور؟»
> *(What is the permitted cancellation deadline? Are there fees or penalties? What happens after repeated no-show?)*

**A second question rides on it and must be answered at the same time:** the **final appointment status
list**. `source ¶52` proposes seven — محجوز (booked), مؤكد (confirmed), وصل المريض (arrived),
قيد العلاج (in-treatment), مكتمل (completed), ملغى (cancelled), لم يحضر (no-show) — and defers the final
list to the clinic. The status vocabulary cannot be separated from the cancellation policy, because
"cancelled by whom, for what reason, under which policy" is what `appointment_status_history` records.

---

## 2. Why it blocks

### 2.1 The Phase 2 tables it unblocks

| Table (`plan.md`) | What DEC-003 fixes about it |
|---|---|
| **`appointment_status_history`** (`plan.md:130`) | The **entire column set**. Does a history row carry a fee? an approval reference? `fromStatus`/`toStatus`, or only a new value? The repository has a direct precedent that the *right* answer is both: the mutable row is current state, the append-only row is the evidence (`V20261005100000:651-655`). |
| **`appointments`** (`plan.md:130`) | The status vocabulary, and whether `status` becomes constrained or stays free text. It is currently **unconstrained text** with `DEFAULT 'PENDING'`. |
| **`waitlist_entries`** (`plan.md:130`) | Whether a late cancellation auto-offers the freed slot to the waitlist, and whether that offer is a policy or a notification. |
| **`reminder_policies` / `reminder_attempts`** (`plan.md:130`) | Whether a reminder is due *before* the notice window closes. |
| *(unnamed)* **approval table** | `plan.md:134` lists "clinic audit, outbox, idempotency, **approval**, and read-model tables" without naming one. §3.5 shows the obvious reuse candidate is **not** reusable. |

### 2.2 The phases it blocks

- **Phase 5** — `plan.md:191` requires create/edit/cancel/reschedule/confirm/arrived/in-treatment/
  completed/cancelled/no-show *"after clinic approval"*; `plan.md:192` requires the reason, actor and
  source to be recorded; `plan.md:196` is literally *"Implement cancellation/minimum-notice/repeated-no-show
  policy only after approval."* The Phase 5 gate (`plan.md:200`) lists cancellation, reschedule and no-show
  acceptance tests.
- **Phase 6** — the cancellation notification and the "reply means cancel/reschedule" handlers
  (`plan.md:213`) are downstream of the status list.
- **Phase 11** — attendance rate and no-show rate (`DENTAL_CLINIC_REQUIREMENTS.md:93`) are undefined until
  the statuses exist.
- **Phase 10** — `plan.md:292` defines a typed `request_cancellation` AI workflow. Until this decision,
  the AI cannot be given a policy to obey, and ADR-0007 Part 2 requires consequential actions to be
  policy-checked, not prompt-checked.

---

## 3. What already exists — verified, not assumed

**Citation convention.** `baseline:NNN` = `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql`
(SHA-256 identical to its mirror under `backend/database/src/main/resources/db/migration/`).
`source ¶N` = 1-based paragraph index of `word/document.xml` in
`source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx`, extracted in document order.

| Claim | Evidence |
|---|---|
| `appointments.status` is **unconstrained text**, `DEFAULT 'PENDING'`, `NOT NULL` | `baseline:624` |
| …and the **table has zero rows**, so a status-vocabulary change currently costs nothing to migrate | `requirements-traceability.md:64` (row A-5) |
| The only Java vocabulary is `PENDING, APPROVED, COMPLETED, CANCELLED, NO_SHOW`, used **as a filter whitelist only** | `ClinicModels.java:175-177` |
| An unknown status filter is **refused**, not dropped, on purpose — "a request parameter that is accepted and then discarded" is ADR 0017's rejected alternative 3 | `ClinicModels.java:169-173` |
| The Spring clinic module is **read-only**: exactly seven `@GetMapping` handlers, zero writes | `ClinicController.java:97,120,136,150,175,211,238` |
| Spring has **no** `appointment:create/reschedule/cancel` permission — *"this module is READ-ONLY (the bounded slice)"* | `Permission.java:132-138` |
| The legacy NestJS vocabulary **does** have all three, mapped to real routes | `packages/auth/src/policy.ts:111-113`; `docs/migration/phases/phase-33-clinic-permissions.md:61-63` |
| **The legacy cancel is a destructive overwrite**: it sets `status: 'CANCELLED'` and *concatenates* `'Cancellation reason: '` into the free-text `notes` column. No actor, no timestamp beyond `updatedAt`, no separate record | `apps/api/src/clinic/local-clinic.adapter.ts:848-854` |
| Reminder replies also write appointment status from the legacy — `CONFIRMED` / `CANCELLED` | `apps/api/src/clinic/reminders.service.ts:268-303, 364-397` |
| ⚠️ `RESCHEDULE_REQUESTED` is a **reminder** status, not an appointment status. The two vocabularies must not be merged by accident | `reminders.service.ts:17` |
| Analytics **already counts by status** with a `row_number()` inside each group, so every status survives | `ClinicRepository.java:163-204` |
| The Arabic source **requires** reason, user and timing on reschedule/cancel — this is a source requirement, not an invention | `source ¶280`: «…تخضعان لسياسة الإلغاء المعتمدة وتسجلان بسبب ومستخدم وتوقيت» |
| The source offers **three** candidate consequences for repeat no-show and defers the choice | `source ¶279`: «…تطبق سياسة العيادة (**تنبيه، تأكيد إضافي، أو موافقة موظف**) بعد اعتمادها» |

### 3.5 There is no reusable approval machinery for a human cancellation decision

`ai_approvals` is scoped to an AI run and **cannot** be reused:

```
"aiRunId"  uuid NOT NULL      -- V20261005100000:636
```

There is no generic approval table. If DEC-003 requires staff approval, a new one is Phase 2 work, and
`plan.md:134` does not name it. Recorded in §7.

### 3.6 Not verified

No live database was inspected. Row counts come from `requirements-traceability.md:64`, which was verified
against the live schema on 2026-10-12 at commit `f182ada` — **not** re-verified here.

---

## 4. Options

### Option C1 — Record-only. Cancellation is always allowed, free, by any authorised role

**Means:** no window, no fee. Cancellation appends to `appointment_status_history` with actor, reason,
source, timestamp — which the Arabic source already requires (`source ¶280`).
**Costs:** no lever against late cancellation and no-show at all; the clinic absorbs it as lost chair time
(DEC-002).
**Forecloses:** nothing. C2/C3/C4 are all *additions* on top of a C1 history table.

### Option C2 — Notice window enforced as a **flag + fee**, with an audited exception path

**Means:** cancelling inside the window still succeeds, is marked as a late cancellation, may raise a fee,
and — if the fee or the lateness is material — may require a named approver. Nothing is blocked.
**Costs:** the fee is a financial event and therefore drags DEC-004 into scope (a fee that is a string in a
note cannot be reported, taxed or refunded); needs a generic approval table (§3.5).
**Forecloses:** nothing about the history model.

### Option C3 — Notice window enforced as a **hard block**

**Means:** cancelling inside the window is refused and the slot is held.
**Costs:** a patient who is ill, in pain, or in an emergency is refused by software. Requires an
unconditional override path for staff, which is C2's exception path with a different label.
**Forecloses:** the clinic's ability to say "call us and we will sort it out" without a system change.

### Option C4 — **Tiered, per-clinic policy**: window + fee + repeat-no-show action + approval threshold, all configuration per project

**Means:** the clinic sets its own values; DEC-013 and DEC-014 already establish that configuration is
per clinic and defaults fail closed.
**Costs:** the most configuration surface of the four, and every value is a number nobody has supplied yet.
**Forecloses:** nothing.

---

## 5. Recommendation — **engineering recommendation, requires clinic sign-off**

**Build the history table and the status vocabulary as a superset; ship C1 behaviour (no fee, no block)
until DEC-003 is answered; then enable C4 with the clinic's own values.**

Concretely, the engineering recommendation is about **columns**, not policy:

1. **`appointment_status_history` records both** — `fromStatus` **and** `toStatus` — not just the new
   value. The repository already states why in `V20261005100000:651-655`: the mutable row is current
   state, the append-only row is the evidence.
2. **Every history row carries `actorUserId` *and* `actorPrincipalType`.** The system has four principal
   types and the AI is one of them (`PrincipalType.AI_AGENT`, `requirements-traceability.md:138`). If
   history records only a user id, a patient-portal cancellation and an AI-proposed cancellation are
   indistinguishable afterwards — and ADR-0007 Part 2 rule 7 requires every AI action to be attributable.
3. **Reserve nullable columns for the fee and the approval reference even though no policy is known.**
   Nullable and inert columns cost nothing; adding them after money starts moving costs a migration on
   financial data. This is the same split DEC-007 recommends for retention columns.
4. **Never record a cancellation reason by appending to `notes`.** That is what the legacy adapter does
   (`local-clinic.adapter.ts:852`) and it destroys the previous note text on every cancellation. The
   history table is the only place a reason may live.
5. **Do not constrain `appointments.status` to a PostgreSQL enum until the clinic answers.** The column
   is unconstrained text today; leaving it unconstrained costs nothing and keeping it unconstrained is
   what makes C1→C4 a configuration change rather than a migration.

**Engineering's view, offered as input and not as policy:** C1 is the only option that is safe to ship
before the answer is known, because it is the only one that blocks nobody. C2 is the natural target for a
clinic that wants a lever; C3 is the only option with a patient-safety cost, and that cost is a clinical
lead judgement, not an engineering one.

---

## 6. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Status vocabulary frozen to the Spring enum's five members | The Arabic source's seven (`booked/confirmed/arrived/in-treatment/completed/cancelled/no-show`) need a new migration **plus** a backfill of in-flight appointments | **Free today** — `appointments` has zero rows (`baseline` census + `requirements-traceability.md:64`). This stops being free the moment a clinic books anything. |
| History table built without `actorPrincipalType` | Staff, AI and portal cancellations become indistinguishable *forever*; ADR-0007 attributability is unmeetable retroactively | **No** |
| Cancellation reason appended to `notes` (the legacy behaviour) | The prior note text is destroyed on each cancellation; no per-cancellation query is possible; no fee can be derived | **No** |
| Fee implemented as free text | It cannot be invoiced, taxed, refunded or reported; DEC-004 cannot reconcile against it | **No** |
| Hard block (C3) shipped with no override | A patient in pain is refused by software, and reception has no in-system way to release the slot | Yes, but the refusal already happened |
| Repeat-no-show action implemented as an automatic block | An automatic punitive action is taken with no human in the loop, and with no appeal | Yes — but a patient has been excluded |

**The asymmetry:** history columns are additive and cheap. **Statuses already written by real users are
not**, and they become real the moment the clinic books an appointment.

---

## 7. Dependencies and conflicts

- **DEC-001** — the notice window is measured against a duration and buffer that do not yet exist.
- **DEC-002** — a cancelled appointment releases its chair; the notice window changes resource utilisation.
- **DEC-004** — a cancellation fee is money. If DEC-004 resolves to "billing out of scope", the fee column
  stays null forever and must be documented as such rather than as "no fee".
- **DEC-005** — cancellation notices and patient replies are channels; the status list determines what a
  reply can mean.
- **DEC-006** — `plan.md:292`'s `request_cancellation` AI workflow must obey the same window and approval.
  ADR-0007 Part 2 puts cancellation in the **consequential** class: *human approval required; the model may
  only request the action*.
- **DEC-014** — per-clinic configurable roles decide who may cancel and who may approve a late cancellation.
- **`plan.md:134`** names an "approval" table without naming a table. **Gap recorded for the orchestrator**
  (§9); I do not own `plan.md`.
- **`ai_approvals` is not reusable** (`V20261005100000:636`, `aiRunId NOT NULL`) — a new table is needed if
  DEC-003 requires staff approval.

---

## 8. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| History table columns | **Yes** | Additive, nullable |
| `fromStatus` omitted at first and added later | **Yes for new rows, no for old** | Old rows have no "before" |
| `actorPrincipalType` omitted at first and added later | **No for historical attribution** | The distinction is unrecoverable once rows exist |
| The status vocabulary itself | **Yes** while `appointments` is empty | **No** once rows exist |
| Adding a status later | Yes, additive | Backfill needed for in-flight appointments |
| A fee once money has moved | **No** | Financial record; cross-link DEC-004 |
| A hard block once patients hit it | Yes | Operational; refusals already happened |

---

## 9. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| 1 | Final appointment status list? (the seven proposed, or another set) | |
| 2 | Notice window before a cancellation is "late" — how long, and is it per service? | |
| 3 | Same window for **rescheduling**, or different? | |
| 4 | Is a late cancellation **blocked**, **allowed with a flag**, or **allowed with a fee**? | |
| 5 | If there is a fee: amount, per service or flat, and is it waived by staff discretion? | |
| 6 | Repeated no-show: what exactly — reminder, extra confirmation, or staff approval? (the source's three candidates, `source ¶279`) | |
| 7 | After how many no-shows? Over what period? | |
| 8 | Which roles may cancel, reschedule, waive a fee, or approve a late cancellation? | |
| 9 | Which actor types may originate a cancellation — staff, AI, patient portal? | |
| 10 | Is a cancellation reason mandatory, and is a controlled vocabulary required? | |

Approver, date, and whether each answer is reversible: ______________________

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007) and Phase 2 can specify `appointment_status_history` with real values.