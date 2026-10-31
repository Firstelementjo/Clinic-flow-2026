# DEC-009 — Odontogram: tooth numbering, surfaces, finding vocabulary, snapshot policy

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinical answer recorded
- **Owner:** Clinical lead
- **Approvers required:** clinical lead (numbering system, surface notation, finding vocabulary, snapshot immutability); clinic owner (storage and retention cost of immutable snapshots — feeds DEC-007); legal (whether an amended clinical finding is permitted at all, and under what record — feeds DEC-007 legal hold)
- **Blocks:** Clinic Phase 2 (`odontogram_snapshots`, `tooth_findings`), Phase 7 (clinical records and odontogram), Phase 8 (treatment-plan items link to a tooth)
- **Related:** DEC-010 (prescriptions and consent share the encounter), DEC-007 (retention and legal hold), DEC-015 / ADR-0022 (Patient is not Customer — the chart is PHI), ADR-0007 Part 2 (AI may not diagnose)

> **This document decides nothing.** `DENTAL_CLINIC_REQUIREMENTS.md:116`: *"Before implementation, the
> clinic must choose the tooth numbering system, surface notation, finding/status vocabulary, image/
> annotation behavior, and whether historical snapshots are immutable. **The agent must not invent a
> clinical standard.**"*
> `plan.md:232` repeats it as a hard gate.

---

## 1. Question

Which **tooth numbering system** does the clinical team use, how are **surfaces** written, what is the
**finding vocabulary**, and is the chart an **immutable snapshot history** or a **mutable current state**?

Four sub-questions, because they are four different kinds of answer:

| # | Sub-question | Kind of answer | Who owns it |
|---|---|---|---|
| A | Tooth numbering system | A standard with a name and a code range | Clinical lead |
| B | Surface notation | A notation *within* a tooth | Clinical lead |
| C | Finding / status vocabulary | A closed or open list of clinical findings | Clinical lead |
| D | Snapshot vs. current-state policy | An engineering/records policy about mutability | Clinical lead + records owner |

---

## 2. FDI or Universal — what the Arabic source actually contains

**This was checked directly against the source document, not inferred.**

**Method.** `source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx` is an OOXML package. `word/document.xml` was
parsed and all **315** `<w:p>` paragraphs were extracted in document order; the `¶N` numbers in this
document are that 1-based index. The package contains **no images**: there is no `word/media/` directory
and `document.xml` contains **0** `<w:drawing>` elements, so no tooth-chart diagram could be carrying the
notation either.

**Finding: the source specifies neither FDI nor Universal — it explicitly defers the choice.**

The entire odontogram requirement in the source is one line:

> `source ¶61` — «توفير مخطط أسنان بصري (Odontogram) لتوثيق حالة الأسنان **وفق معيار يحدده الفريق السريري**»
> *("Provide a visual odontogram to document tooth condition **according to a standard determined by the
> clinical team**.")*

And the entire surface requirement is one line:

> `source ¶62` — «ربط الإجراء بالسن والسطح عند الحاجة، **وفق طريقة التوثيق المعتمدة**»
> *("Link the procedure to the tooth and surface when needed, **per the approved documentation method**.")*

A substring search over all 315 paragraphs for **`FDI`**, **`Universal`**, **`عالمي`** (global), **`دولي`**
(international), **`رقمية`** (numerical) and **`الرقم`** (the number) returns **zero hits in every case**.

The odontogram appears exactly three times in the whole document: `¶61` (the deferral above), `¶83`
(unrelated — "قيمة العلاج **المخطط**", *planned* treatment value) and `¶223`, where it is listed under
**MVP priority 2**, not as a v1 requirement:

> `source ¶223` — «مخطط الأسنان.» under «أولوية ثانية: السجل الطبي» (*priority 2: the clinical record*).

The deferral is preserved verbatim in the English requirements
(`DENTAL_CLINIC_REQUIREMENTS.md:101,116`) and made a build gate in `plan.md:232`.

> ⚠️ **General domain knowledge, not repository evidence.** The descriptions of FDI and Universal/Palmer
> in §4.1 are standard dental nomenclature, not claims drawn from anything in this repository. Nothing in
> this repository indicates which one any specific clinic uses.

---

## 3. Why it blocks

### 3.1 The Phase 2 tables it unblocks

| Table (`plan.md`) | What DEC-009 fixes about it |
|---|---|
| **`odontogram_snapshots`** (`plan.md:131`) | What a snapshot *is*: a full chart image/state at an instant, or a per-encounter frozen chart. Grain, immutability, and whether a snapshot belongs to an encounter or to a point in time independent of one |
| **`tooth_findings`** (`plan.md:131`) | The tooth code representation, the surface representation, the finding code, and whether a finding is *current* or *historical*. Whether the table is append-only or has a `supersededAt` |

### 3.2 The phases it blocks

- **Phase 7** — `plan.md:232`: *"Add odontogram UI/API **only after** clinic selects tooth numbering,
  surface notation, finding vocabulary, and historical snapshot rules."* The whole of work item 2.
- **Phase 7** work item 3 — *"Link procedures to tooth/surface where required"* (`plan.md:233`).
- **Phase 8** work item 2 — *"Link tooth, procedure, dentist, estimated cost, dependency, and target
  visit"* (`plan.md:252`). A plan item cannot reference a tooth that `tooth_findings` cannot name.
- **Phase 7** work item 4 — append-only `clinical_change_events` (`plan.md:234`; `plan.md:138`), which is
  the audit vehicle for every change to the chart.
- **Phase 12** — route `/clinic/patients/:id/odontogram` (`plan.md:340`) cannot be rendered without a
  numbering system and a layout.

---

## 4. What already exists — verified, not assumed

| Claim | Evidence |
|---|---|
| **Neither `odontogram_snapshots` nor `tooth_findings` exists.** A `CREATE TABLE` census across all 27 files in `ops/flyway/sql/` (116 matches) returns neither. | `ops/flyway/sql/` |
| **No odontogram, tooth, surface or finding code exists anywhere in `backend/`.** A search for `odonto`, `FDI`, `Universal`, `tooth`, `أسنان` across `backend/**` returns nothing. | `backend/**` |
| The baseline has a generic `notes` table, which is **not** a structured chart and carries no tooth or surface | `baseline:1125` |
| The repository has already written down the rule this decision needs: a thing that must be answerable *after somebody edits it* is **versioned and effective-dated**, not a single mutable row — *"A single mutable row cannot answer the second question after the fact."* | `BusinessCalendar.java:12-19` |
| The same principle is already applied to money: the rollup pins **both** the id **and** the value, so generation N's arithmetic is frozen | `V20261005090000:38-47` |
| The repo already distinguishes mutable current state from append-only evidence — "the mutable row is the current state and the history is the evidence" | `V20261005100000:651-655` |
| AI suggestions are **non-authoritative** by source requirement: an automated summary "must remain an assistant and under the specialist's review" | `source ¶68`; `DENTAL_CLINIC_REQUIREMENTS.md:112`; `AGENTS.md:16` |
| The chart is PHI and must not enter generic CRM search, ordinary exports, prompts, logs or realtime payloads | `AGENTS.md:15`; ADR-0022 |
| `requirements-traceability.md` records **no odontogram row at all** — the feature was never mapped, because there was no verified behaviour to map | `requirements-traceability.md` (absence) |

### 4.1 The numbering systems, described (domain knowledge — see §2 warning)

| System | Permanent teeth | Primary teeth | Notes |
|---|---|---|---|
| **FDI two-digit** | `11`–`18`, `21`–`28`, `31`–`38`, `41`–`48` | `51`–`55`, `61`–`65`, `71`–`75`, `81`–`85` | First digit = quadrant, second = position. Defined by ISO 3950. Self-describing: the digits carry the quadrant |
| **Universal / Palmer numeric** | `1`–`16` run from the upper right third molar to the upper left third molar; `17`–`32` continue on the lower arch | A–E / 1–5 letter forms | A single number alone does **not** encode the arch or side; the chart does that visually |
| **Palmer quadrant notation** | `1`–`8` inside quadrant letters `A` (upper right), `B` (upper left), `C` (lower left), `D` (lower right); primary uses `E` | as above | The notation used *on* a Universal chart; compact, and quadrant-dependent |

### 4.2 Not verified

No live database was inspected, and no clinic representative has been consulted. The source analysis in
§2 is a text extraction; it does not cover a printed annotation on the original paper document that may
not have been transcribed into the `.docx`.

---

## 5. Options

### 5.1 Sub-question A — numbering

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **N1 — FDI two-digit** | Store `11`–`48` (and `51`–`85`) as the tooth identity | Must add a permanent/primary flag; the code is self-describing, so the chart needs no context to disambiguate | Clinics that use Universal; would require re-coding existing clinical data |
| **N2 — Universal numeric** | Store `1`–`32` as the tooth identity | **A bare number is ambiguous without the arch** — `7` is an upper-left tooth and a lower-right tooth in different schemes. Requires the chart context to be stored or derivable, which is the part that goes wrong quietly. | Clinics that use FDI |
| **N3 — Palmer quadrant** | Store `A1`…`D8` / `E1`…`E5` | Compact and unambiguous, but the letters must be in the type, not a convention | Neither N1 nor N2's bare numeric space |
| **N4 — Neutral internal code + per-clinic rendering** | Store a stable internal tooth id (e.g. `Q1P1` … `Q4P8`) and render it as whatever the clinic chose | One extra mapping layer; the UI, exports and printed documents must all render through it | Nothing — but it is the most engineering to build, and it means the printed chart and the stored code are two different things |

### 5.2 Sub-question B — surfaces

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **S1 — Five named surfaces** (mesial, distal, buccal/facial, lingual/palatal, occlusal) | A closed set per tooth | Incisors and canines have no occlusal surface — the set is per tooth type, not global | Nothing |
| **S2 — Standardised single-letter codes** (`M`, `D`, `B`/`F`, `L`/`P`, `O`) | Codes rather than words, for fast chart entry and compact storage | Requires an explicit AR/EN label mapping — `DENTAL_CLINIC_REQUIREMENTS.md` requires AR/EN throughout | — |
| **S3 — Multi-surface or whole-tooth findings** | A finding may name several surfaces, or the whole tooth | Needs a join table or a constrained array; a naive `text` column is not queryable | — |
| **S4 — No surface model** | Findings are per tooth only | Cheapest | Any procedure that is surface-specific (`plan.md:233`, `source ¶62`) |

### 5.3 Sub-question C — finding vocabulary

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **F1 — Closed, seeded vocabulary** | A clinic-approved list of findings, seeded as reference data, extensible per clinic | Every new finding needs a seed insert | Nothing |
| **F2 — Open free text** | Any finding string | Free to enter | **Everything downstream**: no query, no report, no dependency check, no trend |
| **F3 — Closed vocabulary + per-finding metadata** | as F1, plus attributes (e.g. severity, treatment need, surface applicability) | More design work up front | — |

### 5.4 Sub-question D — snapshot vs. history

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **D1 — Immutable snapshot per encounter** | Each encounter freezes the chart; current state = latest snapshot | Storage grows per encounter; a wrong finding is corrected by a new snapshot | Live editing of the chart |
| **D2 — Mutable current state + full change log** | One current row per tooth; `clinical_change_events` records every change | The log must be complete and correct or history is lost | — |
| **D3 — Both** | Mutable current state, plus frozen snapshots at named milestones (plan approved, treatment completed, consent taken) | The most machinery; needs a rule for which milestones | — |

---

## 6. Recommendation — **engineering recommendation, requires clinical sign-off**

**Build the schema so the *vocabulary is data* and the *stored code is the clinic's own*, and ship D3.**

1. **The tooth, surface and finding vocabularies must be seeded reference tables, not PostgreSQL enums
   and not free text.** This is the single most important recommendation in this brief, and it is
   orthogonal to which system the clinic picks. An enum change on live clinical data is a migration;
   a seed insert is not. The repository already argues against hard-coding a rule the operator must be
   able to change (`V20261005050000:249-252`: *"a policy that is not a column is a policy nobody can change
   without a migration"*).
2. **Store the clinic's chosen code, not a normalised internal code (so: not N4 by default).** A neutral
   internal code makes every printed chart, export and patient-facing document a second rendering path
   that can disagree with the record. One clinic = one system = one code, with the catalogue still open.
   N4 remains available later as a *migration*, which is strictly better than paying for it forever.
3. **Surfaces are a per-tooth-type constrained set, not a global five.** Modelling five surfaces on an
   incisor invents a clinical fact; modelling only occlusal on a molar under-describes it.
4. **`tooth_findings` is append-only with an explicit supersession pointer**, and the current chart is a
   read over the latest non-superseded finding per tooth. This follows the repository's own precedent
   (`V20261005100000:651-655`) and satisfies `plan.md:138` at the same time.
5. **D3: mutable current state, plus named immutable snapshots.** D1 alone stores a snapshot per
   encounter, which is wasteful for a check-up that changes nothing; D2 alone makes "what did the chart
   say on the day the patient consented" unanswerable without a perfectly complete change log.
6. **Snapshot immutability is a records property, and it interacts with DEC-007.** If DEC-007 answers
   "physical purge after N years", an immutable snapshot is destroyed by a purge; if it answers "never
   purge", an immutable snapshot is exactly what legal hold protects. **DEC-009 cannot be signed without
   DEC-007**, and vice versa for this table.

**What the clinical team must still supply, and this brief does not supply:** which system, which surfaces,
which findings, and which milestones freeze a snapshot. Nothing here is an answer to any of those.

---

## 7. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Tooth code as a PostgreSQL `ENUM` or a `CHECK` constraint | Changing system later is `DROP TYPE` / `ALTER` **against clinical data**, with a data backfill and dentist re-coding | **No** — this is DDL on PHI |
| Surface as a `text` column | "Which surfaces need treatment?" is unanswerable; `plan.md:233`'s procedure↔surface link has no target | **No** — information destroyed at write time |
| Findings as free text | No query, no report, no Phase 8 dependency check, no trend analysis; the chart becomes a notes field | **No** |
| Mutable-only (D2) with an incomplete change log | The evidence of what was clinically true at a point in time is gone | **No** |
| Snapshot keyed only to `tooth_findings` | A `treatment_plan_items` tooth link has to resolve through "the latest snapshot", which breaks when a plan references a historical finding | Yes, with a migration — but against live plans |
| Universal assumed where the clinic uses FDI | Every tooth in every stored finding is **silently mislabelled** and looks plausible | **No** — plausible-looking wrong clinical data is the worst failure mode in this document |
| Defaulting to FDI "because it is international" | That is exactly the invented policy `AGENTS.md:19` forbids | **No** |

**The asymmetry:** the catalogue being tables rather than enums is cheap and reversible. The *code a
clinician actually typed into thousands of rows* is not reversible by any migration — it requires
clinician re-coding, patient by patient.

---

## 8. Dependencies and conflicts

- **DEC-007 (privacy / retention)** — bidirectional hard dependency for `odontogram_snapshots`. Immutable
  snapshots and a purge job cannot both be true. DEC-007 §5 recommends retention **columns only** in Phase 2
  and no purge job; this brief adopts that position for snapshots.
- **DEC-010 (prescriptions / consent)** — a consent taken for a treatment plan is evidence about the
  chart state at that moment; if the chart is mutable-only, the consent cannot be bound to what the patient
  saw.
- **DEC-015 / ADR-0022 (Patient is not Customer)** — hard constraint. The odontogram must not appear in
  CRM search, ordinary customer exports, support prompts, logs or realtime payloads without explicit
  policy (`AGENTS.md:15`).
- **ADR-0007 Part 2** — an AI suggestion touching the chart is never authoritative
  (`0007:70`; `source ¶68`); `plan.md:237` keeps clinical suggestions "non-authoritative and
  review-required".
- **Phase 7 gate** (`plan.md:240`) requires *"historical clinical data is not silently overwritten"* —
  which is sub-question D, and is a build gate, not a preference.
- **`plan.md:131` names `tooth_findings` but not a tooth/surface/finding catalogue.** Three reference tables
  are implied and none is named. Gap recorded in §9.

---

## 9. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| Catalogue tables (tooth / surface / finding) seeded as data | **Yes** | A seed insert |
| …as PostgreSQL enums | **No** | `DROP TYPE` against clinical data |
| Surface/finding as a `text` column | **No** | Unstructured at rest |
| Snapshot table shape (a snapshot per encounter vs. per milestone) | **Yes, before any snapshot exists** | After: a backfill and a re-freeze |
| `tooth_findings` append-only with supersession | **Yes** | Additive; a mutable model can be a read view over it |
| **Tooth codes already entered by clinicians** | **No** | Requires clinician re-coding, patient by patient — a clinical exercise, not a migration |
| Snapshot immutability | **No, once declared** | Declaring a snapshot mutable later destroys the immutability that was relied on |

---

## 10. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| A1 | Tooth numbering system: **FDI two-digit**, **Universal**, **Palmer**, or other? | |
| A2 | Permanent and primary teeth both charted, and how are they distinguished? | |
| A3 | Which surfaces apply to which tooth types? | |
| B1 | Surface notation: words, single-letter codes, or multi-surface findings? | |
| C1 | The finding vocabulary — the exact list | |
| C2 | Is the list closed (seeded) or extensible per clinic? | |
| C3 | Does a finding carry attributes (severity, treatment need, active/healed)? | |
| D1 | Immutable snapshot per encounter, mutable current state, or both? | |
| D2 | If both — which milestones freeze a snapshot? | |
| D3 | May a historical finding be corrected in place, or only superseded? | |
| D4 | Are radiographs/images attached to the chart, and do they carry their own findings? | |

Approver, date, and whether each answer is reversible: ______________________

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007) and Phase 2 can specify `odontogram_snapshots` and `tooth_findings` with real values.