# DEC-010 — Prescriptions, consent, e-signature, co-sign, expiry and refusal

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinical or legal answer recorded
- **Owner:** Clinical lead
- **Approvers required:** clinical lead (prescription format, prescriber authority, co-sign rules, expiry); clinic owner (operational: who countersigns, how refusals are handled at the desk); legal (what makes a consent record valid evidence, and how long it must be kept — feeds DEC-007)
- **Blocks:** Clinic Phase 2 (`prescriptions`, `treatment_consents`, `clinical_attachments`), Phase 7 (clinical records), Phase 8 (treatment plans and consent)
- **Related:** DEC-007 (retention and legal hold — consent and prescription are evidence classes of their own), DEC-009 (a consent may need to bind to a chart state), DEC-014 (configurable roles → co-sign must be a capability, not a role), ADR-0022 (prescriptions are PHI)

> **This document decides nothing.** `AGENTS.md:19`: *"Never invent clinic policies for … **consent**, or
> **prescription formats**. Record an open decision."*

---

## 1. Question

In what **format** are prescriptions issued, what **attachments** may accompany a clinical record and what
are the **limits**, is an **electronic signature** required, who may **co-sign**, and how are **expiry** and
**patient refusal** handled?

Five sub-questions, matching `DENTAL_CLINIC_REQUIREMENTS.md:301`:
*"Prescription format, attachment types/limits, consent model/signature, and clinician co-sign rules."*

---

## 2. What the Arabic source does and does not say — verified

**Method.** As in DEC-009 §2: `source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx` → `word/document.xml`,
**315** paragraphs extracted in document order; `¶N` is that 1-based index. No images exist in the package
(no `word/media/`, 0 `<w:drawing>` elements).

| Sub-question | What the source says | Verdict |
|---|---|---|
| **Prescription — who** | `source ¶65`: «تسجيل الوصفات والتعليمات الطبية **من قبل الطبيب المخول**» — *prescriptions and medical instructions are recorded **by the authorised physician*** | ✅ **Settled.** Prescriber authority is the authorised clinician |
| **Prescription — format** | Nothing. No fields, no layout, no language requirement | ❌ **Open** |
| **Consent — that it is required** | `source ¶74`: «تسجيل موافقة المريض **وفق سياسة العيادة ونوع الإجراء**» — *record patient consent **per clinic policy and per procedure type*** | ✅ **Settled.** Consent is required and is policy- and procedure-type-dependent |
| **Consent — signature** | **Nothing.** A substring search for **`توقيع`** (signature) over all 315 paragraphs returns **zero hits** | ❌ **Not a source requirement** — see the warning below |
| **Consent — who consents / capacity** | Nothing | ❌ **Open** |
| **Co-sign** | Nothing | ❌ **Open** |
| **Attachments** | `source ¶45`: «دعم إضافة المرفقات الطبية ذات الصلة **وفق الصلاحيات وسياسة الاحتفاظ بالبيانات**»; `source ¶64`: «إرفاق صور الأشعة والصور والتقارير والملفات ذات الصلة»; `source ¶225`: «الصور والأشعة والمرفقات والوصفات **حسب الحاجة**» | ⚠️ **Principle only.** Permissions + retention + "as needed". **No file types, no size limit, no count limit, no scan requirement** |
| **Prescription appears in** | `source ¶22`, `¶65`, `¶225` — as a role responsibility, a clinical record, and an MVP priority-2 item | — |

> ### ⚠️ The signature question came from **our** English layer, not from the clinic's document
>
> `توقيع` (signature) appears **nowhere** in the 315-paragraph Arabic source. The requirement to
> decide "consent model/signature" originates in the English `DENTAL_CLINIC_REQUIREMENTS.md:301`.
>
> This matters operationally: when the clinic is asked to sign off on e-signature, it should be told that
> **the signature concept was introduced by the translation and requirements layer**, not demanded by their
> own draft. They may reasonably answer "we do not need that", and that would be a correct answer, not a
> deferral.

### 2.1 Not verified

No live database was inspected. No clinic representative or counsel has been consulted. This brief
establishes only what the source text does and does not contain.

---

## 3. Why it blocks

### 3.1 The Phase 2 tables it unblocks

| Table (`plan.md`) | What DEC-010 fixes about it |
|---|---|
| **`prescriptions`** (`plan.md:131`) | Structured-vs-free format; whether drug, dose, route, frequency and duration are first-class columns; who may write; whether it is immutable once issued |
| **`treatment_consents`** (`plan.md:132`) | What a consent **is**: a boolean? an event? a signed artefact? Whether refusal, withdrawal and expiry are representable at all. ⚠️ **A boolean consent makes refusal unrepresentable — see §5.** |
| **`clinical_attachments`** (`plan.md:131`) | Allowed types, size limit, count limit, scan requirement, storage key, checksum, retention class, download audit |
| **`patients` / `patient_consents`** (`plan.md:127`) | Patient-level communication consent is DEC-007's, not this decision's — but they must not be conflated |
| **`clinical_change_events`** (`plan.md:131`) | Append-only evidence for every consent, prescription and attachment change |

### 3.2 The phases it blocks

- **Phase 7** — `plan.md:231` work item 1 (prescriptions and instructions); `plan.md:236` work item 6:
  *"Add **encrypted/object-stored attachments with size/type/virus scanning and download audit**"* — the
  limits are DEC-010's to supply.
- **Phase 7** work item 5 — *"version checks, drafts, signed/finalized state **if approved**, and clinician
  co-sign where required"* (`plan.md:235`).
- **Phase 8** work item 5 — *"Record patient consent, signature/evidence, expiry, refusal, and change
  according to policy"* (`plan.md:255`).
- **Phase 8 gate** — *"Procedure dependencies, consent, approval, clinical execution, and billing
  reconciliation tests pass without state conflation"* (`plan.md:260`).
- **Phase 1** — `plan.md:112` requires *"attachment access policy, retention class, legal hold, download
  audit, and deletion propagation"*; those columns need DEC-010's limits to mean anything.

---

## 4. What already exists — verified, not assumed

**Citation convention.** `baseline:NNN` = `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql`
(SHA-256 identical to its mirror under `backend/database/src/main/resources/db/migration/`).

| Claim | Evidence |
|---|---|
| **Neither `prescriptions` nor `treatment_consents` exists.** `CREATE TABLE` census across all 27 files in `ops/flyway/sql/` (116 matches) returns neither. | `ops/flyway/sql/` |
| **No prescription or consent code exists in `backend/`.** A search for `prescription\|Prescription\|consent\|Consent` across `backend/**` returns exactly **two hits, both prose comments** in AI tool classes. | `AiToolBody.java:39`; `AppointmentProposalTool.java:30` |
| **The existing `documents` table cannot be reused for clinical attachments**: `"knowledgeBaseId" uuid NOT NULL` — every document must belong to a knowledge base | `baseline:952`; `requirements-traceability.md:37` |
| …but `documents` is a good **metadata model** to copy: `storageKey`, `mimeType`, `sizeBytes`, `contentHash`, `uploadedByUserId`, `processedAt` | `baseline:955-965` |
| **Retention machinery exists and is per-tenant / per-class**: `retention_classes` with `retentionDays`, `legalHoldExempt`, `projectId` override, and a most-specific-row resolver index | `V20261005050000:238-263, 277` |
| `legal_holds` exists | `V20261005050000:289` |
| **Export machinery already provides signed URLs, checksum, retention class, authorisation snapshot and download events** — the controls `AGENTS.md:27` demands of attachments already exist for exports | `V20261005070000:158-183`; `export_download_events` at `:469` |
| **There is no reusable generic approval table**: `ai_approvals` is scoped to an AI run | `V20261005100000:636` (`"aiRunId" uuid NOT NULL`) |
| Co-sign must be a **capability, not a role value** — `UserRole` is a fixed enum, and DEC-014 makes roles configurable per clinic | `phase-33-clinic-permissions.md:129-137`; `open-decisions.md:19` |
| Prescriptions and clinical history must not enter CRM search, exports, prompts, logs or realtime payloads | `AGENTS.md:15`; ADR-0022 |
| **The AI may not prescribe** | `source ¶68`; `AGENTS.md:16`; `0007:70` |
| There is **no allergy or medication storage** for a prescription to be checked against — `patients` has none, and patient attachments are MISSING | `requirements-traceability.md:36-37` (rows P-7, P-8) |

**Naming caution.** `patients.patient_consents` (`plan.md:127`) and `treatment_consents` (`plan.md:132`)
are two different things — communication consent vs. treatment consent. They must not share a table.
Recorded in §9.

---

## 5. Options

### 5.1 Prescription format

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **P1 — Structured only** | Drug, dose, route, frequency, duration, quantity, instructions as columns | Every clinical nuance must fit the columns; free-text instructions still needed in practice | Nothing |
| **P2 — Free text only** | A prescription is a block of clinical text | Free to write | Drug-level querying, interaction/allergy checking, structured printing, any report |
| **P3 — Structured header + free-text instructions, both** | Structured part mandatory and queryable; instructions free-text and translatable | Two fields to render, two to validate | Nothing |

### 5.2 Consent model — **the highest-consequence choice in this document**

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **N1 — Boolean `consented BOOLEAN`** | A flag on the plan or the procedure | Almost free | **Refusal, withdrawal, expiry, and the version of the text shown are all unrepresentable.** A patient who says *no* and a patient who was never asked are the same value. |
| **N2 — Consent as an auditable event** | An append-only record: who consented, when, for what scope, which version of the wording was shown, by whom it was captured | More columns; needs a versioned consent template | — |
| **N3 — Signature artefact plus event** | As N2, plus a drawn/typed signature stored as an attachment, bound to a hash of the exact text shown | Storage, retention and display work; needs a decision about what exactly is signed | — |
| **N4 — Two-tier: event always, signature artefact only for named procedure categories** | Consent is always an event; a signature is required only where the clinic says so | One extra category table | Nothing; the clinic chooses the categories |

### 5.3 Co-sign

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **S1 — Single prescriber** | One authorised clinician signs | None | Second opinion for high-risk procedures |
| **S2 — Countersign required for a named procedure category** (e.g. sedation, oral surgery) | A second authorised clinician must countersign before the prescription/plan is active | Needs a pending → active state and a capability | Nothing |
| **S3 — Any second authorised clinician may countersign** | Optional second signature | Lowest cost | A meaningful *review* gate — self-countersignature is trivial |

### 5.4 Attachments

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **T1 — Metadata only, limits decided at Phase 2** | `mimeType`, `sizeBytes`, `contentHash`, `storageKey`, retention class, scan status — with the *allowed type list* and *max size* as per-clinic configuration | Needs configuration UI (Phase 12) | Nothing |
| **T2 — Hard-coded platform-wide limits** | e.g. one fixed type list and one fixed max size | Simplest | A clinic that accepts a format the platform refuses |
| **T3 — No attachments in v1** | Phase 7 work item 6 is waived; radiographs stay outside the product | Zero | MVP priority 2 (`source ¶225`) is only partly delivered |

### 5.5 Expiry and refusal

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **E1 — Expiry required for every consent** | A consent has an expiry; expired consent blocks the dependent item | Every consent needs a period, supplied by the clinic | Long-horizon plans (implants, orthodontics) |
| **E2 — No expiry; consent lasts for the plan** | — | Simplest | Re-consent for a changed plan |
| **E3 — Expiry optional per consent type; default none** | Some categories expire, some do not | One nullable column | Nothing |

---

## 6. Recommendation — **engineering recommendation, requires clinical and legal sign-off**

**P3 + N4 + S2-shaped + T1 + E3, with refusal as a first-class append-only record in all of them.**

1. **Consent is an append-only event with a template version, never a boolean** (N2 minimum, N4 as the
   shape). A consent record must be able to answer, on its own: *who consented, when, for which procedure
   category, against which version of which wording, captured by whom, and has it since been withdrawn,
   refused or superseded.* A boolean answers none of those and is **unrecoverable once written**.
2. **Refusal is a record, not the absence of a record** — and it is never deletable. The engineer's
   concern is specific: if refusal is modelled as "no consent row exists", a later consent can be
   recorded over it and the original refusal is gone. That destroys the only thing the clinic would need
   in a dispute. Model `refusedAt` / `refusedBy` / `reason` on the consent aggregate, never as absence.
3. **P3 for prescriptions.** The structured part is what makes a drug list queryable and printable in
   Arabic and English; the free-text part is what makes it clinically usable. Requiring only one of the
   two is the mistake.
4. **Co-sign is a capability, and it defaults OFF** (S2's shape, off). `UserRole` is a fixed enum and
   DEC-014 layers configurability above it; a co-sign rule expressed as "a dentist must countersign"
   cannot survive DEC-014 without becoming a capability anyway.
5. **T1 for attachments.** Copy the `documents` **metadata shape** (`baseline:955-965`) and reuse the
   **export controls** that already exist — signed URL, checksum, retention class, authorisation snapshot,
   download event (`V20261005070000:158-183, 469`) — rather than inventing a second mechanism. The allowed
   type list and max size are **clinic configuration**, and they stay null/unenforced until DEC-010 is
   answered, matching DEC-007's "columns only, no behaviour yet" position.
6. **Do not build a signature mechanism until it is asked for.** The source does not contain the word.
   If it is asked for, the thing that must be bound is **the exact text version the patient saw** — a
   signature on a PDF with no link to the template version is evidence of nothing.
7. **A prescription cannot be checked against allergies today, because no allergy field exists**
   (`requirements-traceability.md:36`). Say so in the UI rather than implying a check that does not run.

**What the clinical lead and counsel must still supply, and this brief does not supply:** the prescription
fields, the consent wording and its version, the signature requirement and per-category list, the co-sign
categories, the attachment limits, and the expiry periods. Nothing here is an answer to any of those.

---

## 7. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| `consented BOOLEAN` | Refusal, withdrawal, expiry and the wording shown become unrepresentable; a consent dispute cannot be answered; a later consent can be written over an earlier refusal | **No** |
| Refusal modelled as absence of a row | The original refusal is erased by the next consent; nothing warns anyone | **No** |
| Free-text-only prescription | Drug-level reporting, structured printing and any future interaction check are impossible | **No** |
| Structured-only prescription with no free-text field | Clinicians work around it in a notes field, and the structured data stops being trustworthy | Partially — by re-keying |
| Attachment limits baked into a `CHECK` constraint or an enum | Per-clinic limits become a migration; a clinic that accepts a refused format is blocked in the database | **No** if an enum |
| Attachment with no `retentionClass` | The retention and legal-hold machinery has no key to join on; deletion propagation has no unit of work | Yes, additively — but orphan files need a sweep |
| Signature implemented without binding to a template version | The signature is an image attached to nothing; it evidences nothing in a dispute | **No** — the evidence is not recoverable retroactively |
| Co-sign expressed as a role check | Broken the moment DEC-014 makes roles configurable per clinic | Yes, but every rule needs rewriting |
| Prescriptions reachable through CRM search or ordinary exports | PHI leaks through a path that already exists and is well built | **No** — the disclosure already happened |

**The asymmetry:** limit columns and template-version columns are additive and free. **A consent record,
a refusal, and a signature are evidence — they are written once and are never rewritten.**

---

## 8. Dependencies and conflicts

- **DEC-007 (privacy / retention)** — bidirectional. A consent is an **evidence class distinct from a
  clinical note**: DEC-007 §7 states that *"a consent record that expires on the same schedule as a note
  may destroy the proof that treatment was agreed."* A prescription has the same property. Both need their
  own retention answer, and DEC-007 §9 already carries that question (B4).
- **DEC-009 (odontogram)** — if consent is bound to what the patient was shown, and the chart is
  mutable-only, the binding is to a state that has since changed.
- **DEC-014 (configurable per-clinic roles)** — co-sign must be expressed as a capability bundle, not a
  role name. `open-decisions.md:89-91` is explicit that `Permission`/`RolePermissions` are not replaced.
- **ADR-0022 (Patient is not Customer)** — hard constraint. Prescriptions, consents and attachments must
  not reach generic CRM search, ordinary exports, prompts, logs or realtime payloads (`AGENTS.md:15`).
- **ADR-0007 Part 2** — issuing or amending a prescription is a **consequential** action
  (`0007:70`); AI may only request it. `AGENTS.md:16` and `source ¶68` both bar the model from prescribing.
- **Phase 1 gate** (`plan.md:117`) — the cross-role negative tests for attachment access cannot be written
  until the limits are known.
- **⚠️ Name collision:** `plan.md:127` lists `patient_consents` (communication consent, DEC-007's) and
  `plan.md:132` lists `treatment_consents` (clinical consent, this decision's). Two similarly-named tables
  for two different consents. Recorded in §9.

---

## 9. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| Attachment metadata columns | **Yes** | Additive |
| Attachment type list / size limit as configuration | **Yes** | A config change |
| …as an enum or `CHECK` | **No** | `DROP`/`ALTER` against stored attachments |
| Prescription structured columns | **Yes** | Additive |
| Free-text prescription content | **No** | Clinical re-keying |
| Consent template versioning | **Yes, before any consent exists** | After: re-consent, or an unversioned historical gap |
| **A written consent record** | **No** | Append-only evidence |
| **A written refusal** | **No** | Must never be deletable |
| **A signature artefact** | **No** | It is evidence of what was agreed |
| Co-sign expressed as a capability | **Yes** | A configuration change |

---

## 10. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| 1 | Prescription format: which structured fields are mandatory, and is free-text instruction allowed? | |
| 2 | Which languages must a prescription be issued in, and is a printed copy required? | |
| 3 | Is a prescription immutable once issued, or amendable with a version? | |
| 4 | Who may prescribe, and does that differ from "authorised clinician" in `source ¶65`? | |
| 5 | **Is a signature required at all?** (the source never says) | |
| 6 | If yes — what is signed: a rendered document, a template version, or an event? | |
| 7 | Which procedure categories require a signature, and which require co-sign? | |
| 8 | Who may countersign, and may a clinician countersign their own entry? | |
| 9 | Consent expiry — required, optional per category, or never? Per-category periods? | |
| 10 | How is **refusal** recorded, displayed, and how long is a refusal retained? | |
| 11 | Withdrawn consent: is treatment already given affected, and who is notified? | |
| 12 | Attachments: allowed file types, max size, max count per record, and is virus scanning required? | |
| 13 | Where are attachments stored, and what is the retention class? (DEC-007 B2) | |
| 14 | Confirm the split between `patient_consents` (communication) and `treatment_consents` (clinical)? | |

Approver, date, and whether each answer is reversible: ______________________

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007) and Phase 2 can specify `prescriptions` and `treatment_consents` with real values.