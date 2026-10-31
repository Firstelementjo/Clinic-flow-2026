# DEC-007 — retention floor: what was answered, and the trap in the existing columns

**Status:** ANSWERED as a provisional value. Enforcement is **not** yet buildable.
**Answered:** 2026-10-13 by Jordan.
**Owner of the outstanding part:** whoever gives legal confirmation of the basis.

---

## What was answered

```
retention_floor_years = 10
anchor                = last procedure
rule                  = no physical deletion before the floor
rule                  = legal hold blocks deletion
basis                 = "analogy to Jordan private hospitals regulation,
                         pending legal confirmation"
```

Three of the four rules are directly implementable. The fourth — and the schema's existing
column names — need care.

---

## The trap: a floor is not an expiry

The `patients` table already has a column called `retentionExpiresAt`. It is nullable and inert,
added in `V20261013000000__clinic_patients.sql`.

It is tempting to write:

```
retentionExpiresAt = lastProcedureAt + 10 years
```

**That would convert a floor into a ceiling, and it is the one thing here that could cause real
harm.**

- A **floor** says *keep this at least N years*. It permits keeping it longer.
- An **expiry** says *this may be deleted after N years*. It permits deletion at N.

Filling `retentionExpiresAt` from `retention_floor_years` would therefore create a deletion
authorisation that the decision never granted, in a table holding protected clinical data, on the
basis of a regulation the answer itself describes as *"pending legal confirmation."*

**`retentionExpiresAt` stays NULL.** It is not the right column for a floor, and it will not be
populated from this answer.

---

## The second problem: "last procedure" has no source yet

The anchor is *last procedure*. What can supply it today?

| candidate | is it "last procedure"? |
| --- | --- |
| `procedures` table | **No** — it is the service catalogue. One row per service offered, not per treatment. |
| `appointments.procedureId` | **No, and it is dangerous** — an appointment is a *booking*, not a performed treatment. |
| Phase 8 treatment records | **Yes** — and they do not exist. |

Using `appointments` as the anchor would anchor to the last *booked* appointment. A treatment
performed eight years ago with no appointment since would anchor to an unrelated recent booking,
making the computed floor *shorter* than ten years and pushing the row toward deletion sooner.

That is the failure direction that matters: an over-recent anchor understates elapsed time and
shortens protection. So the anchor column is created **empty** and is not filled from appointments.

---

## What was built

Nothing was written to the database for this answer, and that is the correct outcome. Two things
are recorded instead.

### 1. The provisional nature of the value

The basis is self-declared as *pending legal confirmation*. A value whose own justification is
provisional must not be encoded as an enforcement threshold. It is recorded in
`docs/clinic/answers.md` with its basis and its provisional status, not as a database default.

### 2. Legal hold — already enforced, and already tested

`patients_legal_hold_blocks_deletion`

```sql
CHECK (NOT "legalHold" OR "deletedAt" IS NULL)
```

This covers soft deletion, and `PatientServiceIntegrationTest` asserts the database refuses to set
`deletedAt` on a held row. That part of DEC-007 is **done**, not pending.

It does not cover *physical* deletion, because no physical deletion exists anywhere in this
system. There is no purge job and none was built.

---

## What is needed before the floor can be enforced

| prerequisite | why |
| --- | --- |
| Phase 8 treatment records | the only honest source for "last procedure" |
| a per-tenant floor column | Jordan's 10 years is an analogy; a tenant may be governed by something stricter |
| legal confirmation of the basis | until then the number is provisional |
| a physical-deletion path | a floor is only meaningful against something that deletes |
| `legal-hold blocks physical deletion` | the soft-delete CHECK does not cover it |

When those exist, the control is a CHECK comparable to the legal-hold one:

```
no physical deletion while legalHold
no physical deletion while now < lastProcedureAt + floor years
```

expressible as a row invariant and enforced at the database, which is how the legal-hold rule is
already enforced rather than left to whichever service remembers.

---

## For the record: what was deliberately not done

- `retentionExpiresAt` was **not** populated. See the floor/ceiling section.
- No purge job, no scheduled task, no deletion behaviour.
- `retention_floor_years = 10` was **not** written as a column default, because a per-tenant
  policy written as a global default is the first step of getting it wrong for someone.
- `appointments` was **not** used as the anchor.

No agent may convert an unanswered part of this into a default policy.