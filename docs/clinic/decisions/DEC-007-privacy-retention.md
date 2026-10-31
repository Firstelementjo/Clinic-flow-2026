# DEC-007 — Privacy, medical-record retention, export/delete authority, legal hold

- **Status:** OPEN — drafted by orchestrator 2026-10-13, awaiting clinic + legal sign-off
- **Owner:** Clinic principal (clinical policy) **and** legal counsel (law, authority, holds)
- **Approvers required:** Clinic principal, clinical lead, legal counsel, security, operations
- **Blocks:** Clinic Phase 1 gate (PHI policy), Clinic Phase 2 (schema), and every downstream phase that stores clinical data
- **Related:** DEC-004 (billing retention), DEC-010 (prescription/consent formats), DEC-015 (Patient ≠ Customer), ADR-0008 (operational retention precedent), ADR-0007 rule 7

> **This document does not decide anything.** `firstelement-dental-clinic-feature-kit/.../plan.md` line 17
> states: *"Do not invent a tooth numbering system, clinical status list, triage protocol, cancellation
> policy, tax rule, **retention period**, resource model, or payment gateway behavior."*
> Every option below is a genuine choice for the clinic and its lawyer. The recommendations are
> engineering recommendations about *structure and sequencing*, and are labelled as such.

---

## 1. The question

Split into five that must each be answered:

| # | Question |
|---|---|
| **A** | What is the applicable law and jurisdiction for retained clinical records? |
| **B** | What retention period applies to each class of record (clinical note, odontogram, prescription, attachment/X-ray, invoice)? |
| **C** | Is deletion physical, logical, or anonymising? Who may authorise it? |
| **D** | Who may export clinical data, and does a patient have a right to their own record? |
| **E** | What does a legal hold suspend, and who may place and release one? |

---

## 2. Why this blocks work that has already started

Phase 2 ("Schema and persistence foundation") lists these rules, quoted:

> "Use explicit tenant/clinic/location/project indexes, foreign keys, unique constraints, optimistic
> version fields, effective timestamps, source fields, and **retention metadata**. Use append-only
> history for clinical, appointment status, treatment, payment, and approval changes."

So **every** Phase 2 table is specified to carry retention metadata. That metadata cannot be designed
without answers to B and E, because the columns differ between "we will physically purge after N
years" and "we never purge, we only tombstone":

- physical purge implies `purgeAfter`, a purge job, and a hard-delete ordering across FKs
- tombstone-only implies `deletedAt`, `deletionReason`, `deletedBy`, and **no** purge job at all

Phase 1's gate additionally requires *"No PHI appears in ordinary CRM, logs, exports, or realtime
payloads"* — which is a statement about **D** and about field classification, and cannot be proven
until D is answered.

Blocks these Phase 2 tables directly: `patients`, `patient_identities`, `patient_consents`,
`patient_attachments`, `clinical_encounters`, `clinical_notes`, `diagnosis_entries`,
`odontogram_snapshots`, `tooth_findings`, `clinical_procedures`, `prescriptions`,
`clinical_attachments`, `clinical_change_events`, `treatment_plans`, `treatment_plan_items`,
`treatment_consents`, `clinic_invoices`, and `receipts`.

---

## 3. What already exists — this is not starting from zero

Three things can be reused rather than invented. This materially narrows the decision.

**3.1 An operational retention precedent (ADR-0008).** Already accepted, and already sets the
principle this decision should follow rather than re-argue:

> "Retention never deletes a row a human decision depends on."

with purge being **tenant-scoped, batched and audited**, and dead letters / `DEAD` rows exempt
because deleting them destroys the evidence for "we did not replay this".

**3.2 A repeated, deliberate refusal to ship retention behaviour early.** Two migrations record the
same decision in their own comments — `V20261004000100__outbox.sql` and
`V20261004000700__job_attempts.sql`, both containing:

> "RESOLVED AS: no retention behaviour is created here, deliberately. Nothing in this migration
> deletes a row. A retention policy is a destructive, audited, tenant-scoped purge that needs the
> [requirements] first."

A third, `V20261004001000__idempotency_records.sql`, discusses purge semantics without recording
that decision.

**3.3 The CRM side already requires the Admin surface.** `PROJECT_REQUIREMENTS.md` L156 lists
"audit/security events, **retention classes, legal holds, privacy deletion jobs**, and export
activity", and L296 states "Retention classes and legal hold are explicit."

So the retention *machinery* is already a committed requirement of the existing product. What is
missing is only the **clinical** policy: periods, authority, and hold semantics.

---

## 4. Options

### A. Applicable law and jurisdiction

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **A1** | Jurisdiction declared up front; periods derived from that law | Requires legal input before Phase 2 | Nothing |
| **A2** | Build jurisdiction-agnostic structure, treat period as configuration per tenant | Configurable, but "who configures the period" becomes a new authority question | A single hard-coded default |
| **A3** | Defer entirely | Phase 2 blocked; clinical tables cannot be created | — |

**Recommendation: A1.** The period is a legal fact, not a product preference. Designing around it
as configuration risks every tenant being configured to the same wrong value by someone who did not
know it was a legal question.

### B. Retention period per record class

Distinct classes have genuinely different answers: an X-ray film is bulk data, a clinical note is
the legal record of care, an invoice is a tax document, a consent form is evidence of a decision.

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **B1** | One period for all clinical data | Simplest | Per-class optimisation; legally wrong for at least one class |
| **B2** | Per-class periods (clinical / attachment / financial / consent), each legally supplied | Four numbers to obtain | Nothing |
| **B3** | Indefinite retention, no expiry | No purge risk | Right-to-erasure cannot be honoured; storage grows without bound |

**Recommendation: B2.** The classes already exist in the Phase 2 table list — this is grouping
existing tables, not inventing taxonomy. B1 and B3 are both likely unlawful in at least one
jurisdiction, and B3 makes erasure impossible by construction.

### C. Deletion: physical, logical, or anonymising

This is the highest-consequence choice, because it is the only irreversible one.

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **C1** | Logical tombstone only in v1: `deletedAt`, `deletionReason`, `deletedBy`, row retained | Near-zero risk; reversible | Genuine erasure of content |
| **C2** | Physical deletion by a tenant-scoped, batched, audited purge job after the class period | Real erasure | Nothing, but irreversible; and it conflicts with ADR-0008 rule 1 for anything a decision depends on |
| **C3** | Anonymise in place: strip direct identifiers, keep the clinical shape for aggregate reporting | Preserves statistics, loses linkage | Re-identification for the patient; needs a defined anonymisation standard |
| **C4** | C1 now; C2 later, only for classes the clinic confirms are not "a human decision depends on" | Staged | Requires the per-class ruling from B first |

**Recommendation: C4 (C1 in v1).** It follows ADR-0008's existing rule rather than contradicting it,
and it means the irreversible door is not opened in the same phase as the schema. C2 must never apply
to append-only history — `clinical_change_events`, `appointment_status_history`,
`treatment_status_history` are evidence by construction.

### D. Export authority and patient access

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **D1** | Export is role-based within the clinic; no patient-facing export in v1 | Simple, matches DEC-011 (console is ours alone) | Patient self-service |
| **D2** | Role-based **plus** a patient access route to their own record | Needs a separate authenticated principal and a re-identification path | — |
| **D3** | Role-based, patient access exists but is request-driven through the clinic | Operational workload on reception | — |

**Recommendation: D1 for v1, with D3 as the target.** Patient self-service requires a principal type
that does not exist yet and a verified-identity story that DEC-014 does not cover. D1 keeps Phase 2
moving; D3 delivers the outcome without inventing an authentication model.

Note the existing export machinery already enforces much of this: exports are asynchronous, redacted,
tenant-scoped, signed, and audited. Those controls transfer to clinical exports rather than being
rebuilt.

### E. Legal hold

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **E1** | Hold is a flag on the record; it suspends retention expiry and blocks purge | Simple | — |
| **E2** | Hold is a flag plus a scope (a patient, a date range, a case) | More precise | Slightly more machinery |
| **E3** | No hold concept in v1 | Zero | Cannot comply with a preservation notice; high legal risk |

**Recommendation: E2, with E1 as the floor.** `PROJECT_REQUIREMENTS` already commits the product to
exposing legal holds, so E3 is not available. Scope matters because a real preservation notice is
usually a patient or a date range, not the whole clinic.

---

## 5. Recommended sequence — the part engineering can commit to now

The valuable move is to separate **structure** from **values**, because structure is needed by
Phase 2 and values are what the clinic must supply.

1. **Phase 2 adds retention *columns* only.** Proposed shape, all nullable and all inert:
   `retentionClass`, `retentionExpiresAt`, `legalHold`, `legalHoldScope`, `legalHoldReference`,
   `deletedAt`, `deletionReason`, `deletedBy`.
   No purge job, no scheduled task, no deletion behaviour. This matches the existing
   `RESOLVED AS: no retention behaviour is created here, deliberately` precedent.
2. **`retentionClass` is a value the clinic must supply.** Until B is answered it is `null` for
   every row — which is honest, and fails closed in the sense that nothing can be purged because
   nothing has a period.
3. **DEC-015 makes a mistake here expensive.** `Patient` is not `Customer`. Any CRM-side retention
   or deletion path must **not** be allowed to reach patient or clinical tables. If the CRM data
   lifecycle is extended without this guard, a customer purge becomes a clinical-record purge. This
   guard is required regardless of how B, C and E are answered.
4. **Append-only history is exempt from purge by construction**, not by configuration — enforced by
   the same trigger style ADR-0008 relies on, so no future code change can enable it accidentally.

---

## 6. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Period too short | Clinical records destroyed that law required to be kept; potential unlawful deletion of the legal record of care | **No.** Irreversible |
| Physical purge where logical was intended | Loss of the audit/evidence trail that ADR-0008 rule 1 protects | **No** |
| No legal hold | Cannot comply with a preservation notice; records may be destroyed under an order that forbade it | **No** |
| CRM deletion reaching patient tables | Clinical data destroyed by a customer-data request (DEC-015 violation) | **No** |
| Export authority too broad | PHI leaves the clinic in bulk; the breach is the export, not the permission bug | No |
| Columns added for a policy later abandoned | Additive and nullable, so harmless | **Yes** — columns are cheap to stop using |

The asymmetry is the point: the *columns* are cheap and reversible, the *purge job* and the
*period* are not. That is why the recommendation splits them.

---

## 7. Dependencies and conflicts

- **DEC-015 (Patient ≠ Customer)** — hard constraint. Governs point 3.5 above. Must not be weakened.
- **DEC-004 (billing/tax)** — invoice and receipt retention is a *tax* question, not a clinical one.
  The financial classes should be answered with the finance owner, not the clinical lead.
- **DEC-010 (prescriptions/consent)** — `prescriptions` and `treatment_consents` have their own
  evidentiary rules; a consent record that expires on the same schedule as a note may destroy the
  proof that treatment was agreed.
- **ADR-0008** — reuse its rule and its purge discipline; do not invent a second retention style.
- **ADR-0007 rule 7** — no payment execution. Not directly in conflict, but it is why DEC-004 must
  state whether billing is in scope before any financial retention period is written down.
- **Phase 1 gate** — cannot be proven green until D is answered, because the gate is a
  non-disclosure property.

---

## 8. Reversibility

- The proposed **columns** are additive and nullable: fully reversible, and unused columns cost
  nothing if the policy changes.
- A **retention period** is reversible only before anything is purged. Once a purge job has run, it
  is not reversible. This is the single irreversible decision in this document.
- **Logical deletion** is reversible by design; **physical deletion and anonymisation are not**,
  and anonymisation is only reversible if the key is retained separately, which is itself a
  retention decision.

---

## 9. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| A | Jurisdiction and applicable law? | |
| B1 | Retention period — clinical notes/encounters? | |
| B2 | Retention period — attachments and imaging? | |
| B3 | Retention period — invoices and receipts (finance-owned)? | |
| B4 | Retention period — consents and prescriptions? | |
| C | Logical-only, or physical purge after the period? Which classes? | |
| D | Export authority — which roles? Patient self-access in v1? | |
| E | Legal hold — scope and who may place/release? | |

Approver, date, and whether each answer is reversible: ______________________

Once answered, this becomes ADR-0024 and Phase 2 can specify retention columns with real values.