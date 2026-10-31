# DEC-004 — Clinic billing and payments, and its conflict with ADR-0007 rule 7

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinic answer recorded, **and the ADR conflict is unresolved**
- **Owner:** Clinic **and** finance
- **Approvers required:** clinic owner / authorized manager; finance officer or accountant; legal or tax adviser (tax rate, official numbering, invoice format); **the owner of ADR-0007** must co-sign any amendment to rule 7. Engineering must not edit ADR-0007.
- **Blocks:** Clinic Phase 2 (eight tables), Phase 8 (estimated vs invoiced vs collected), Phase 9 (clinic billing), Phase 11 (financial dashboard and reports)
- **Related:** ADR-0007 Part 1 rules 1–6 and **rule 7**, `PROJECT_REQUIREMENTS.md` §FR-7 clause 8, DEC-003 (a cancellation fee is money), DEC-008 (a clinic is a `Project` — and an official invoice number belongs to a legal entity, not a project), DEC-014 (who may approve a refund)

> **This document decides nothing.** `firstelement-dental-clinic-feature-kit/.../plan.md:17`: *"Do not
> invent a … **tax rule** … or **payment gateway behavior**."*
> `plan.md:276` (Phase 9 work item 7): *"Defer external payment gateway and tax/legal invoice rules
> until clinic decision."*

---

## 1. Question

Broken into the seven sub-questions the register and the Arabic source actually ask:

1. **When** is an invoice raised — before treatment, after treatment, or both?
2. Are there **deposits** and **instalments**?
3. Which **currencies** and **payment methods** are accepted?
4. Is **tax** applied?
5. Is **official invoice numbering** required?
6. What is **deferred** — is a gateway in scope at all, or does the clinic record money it took elsewhere?
7. **Does clinic billing amend ADR-0007 rule 7, or does billing stay out of scope?**

`source ¶307`, verbatim:

> «متى تصدر الفاتورة (قبل العلاج أم بعده)؟ هل توجد دفعات مقدمة أو أقساط؟ ما العملات وطرق الدفع؟ هل يلزم ضريبة أو ترقيم رسمي للفواتير؟»
> *(When is the invoice issued — before treatment or after? Are there deposits or instalments? What currencies and payment methods? Is a tax or official invoice numbering required?)*

Sub-question 7 is not in the source. It is imposed by this repository — see §2.

---

## 2. The ADR-0007 rule 7 conflict, stated precisely

### 2.1 The rule

`docs/migration/adr/0007-finance-and-ai-policy.md:45-48`, Part 1 rule 7:

> "**No payment execution and no external billing.** This is an internal finance and usage surface.
> Invoices and payment credentials are explicitly out of scope for this migration and remain so
> **unless a separate requirement is raised** (`PROJECT_REQUIREMENTS` §FR-7.8)."

The rule is **Accepted** (`0007:4`) and was written for a migration that had no clinic vertical. It has
been carried into the DDL as an explicit, deliberate choice rather than an omission:

> "NO PAYMENT AND NO INVOICING TABLES. ADR-0007 rule 7: … Nothing in this file stores a card, a mandate,
> an invoice number or a payment state, **and that is a decision rather than an omission**."
> — `V20261005090000__finance_rate_cards_periods.sql:28-32`

### 2.2 The cross-reference is valid — verified

ADR-0007 cites `PROJECT_REQUIREMENTS` §FR-7.8. That clause exists and says exactly what ADR-0007 assumes
it says (`PROJECT_REQUIREMENTS.md:163-174`, FR-7 "Finance dashboard", clause 8 at line 174):

> "Add a later extension point for **invoices or external billing**, but **do not create payment flows in
> this migration without a separate requirement**."

FR-7's own preamble (line 165) repeats the limit: *"It does not authorize payment execution or external
billing changes."*

**So the rule has always had an escape hatch, and the hatch has two halves: an extension point for
invoices *is* authorised; payment flows are authorised only by a *separate requirement*.**

### 2.3 The separate requirement now exists

| Source | What it says |
|---|---|
| Arabic source `¶77-83` | Create an invoice linked to patient/visit/procedures; record partial and full payments and the method; show balance; support discounts, refunds and adjustments within delegated authority and audit; issue a receipt when required; report by period, dentist and service; **distinguish estimated treatment value, issued invoice value, and actually collected value** |
| `DENTAL_CLINIC_REQUIREMENTS.md:134-145` | Same, plus: *"The first feature release **records payments and produces financial truth**. External payment gateway execution, tax/legal invoice numbering, installments, deposits, and multi-currency rules **require clinic confirmation before implementation**."* |
| `plan.md:133` | Lists `clinic_invoices`, `clinic_invoice_lines`, `payments`, `payment_allocations`, `refunds`, `discounts`, `financial_adjustments`, `receipts` |
| `plan.md:270-276` (Phase 9) | Build them; **defer** gateway and tax/legal invoice rules |

**Reading of the conflict, stated as engineering's position and not as a ruling:** the Arabic source
**records** payments and **defers** gateway execution and tax/numbering. ADR-0007 rule 7 forbids
**execution** and **credentials**, and defers **invoices** to FR-7.8's extension point. Those two
positions do not actually collide on the part the source asks for. They collide only on the word
"billing" if it is read as including tax and official numbering.

---

## 3. Why it blocks

### 3.1 The Phase 2 tables it unblocks — all eight (`plan.md:133`)

| Table | Blocked on |
|---|---|
| **`clinic_invoices`** | Invoice timing; whether an invoice is mutable or append-only; whether it carries an official number; status vocabulary |
| **`clinic_invoice_lines`** | Whether a line points at a procedure, a treatment-plan item, or a free-text service; whether price is snapshotted or joined |
| **`payments`** | Method vocabulary; whether a payment may be recorded against a patient without an invoice; **whether any payment credential is ever stored** (it must not be — see §7.1) |
| **`payment_allocations`** | Whether one payment may settle several invoices and one invoice several payments; partial-payment rules |
| **`refunds`** | Approval thresholds; whether a refund reverses an allocation or creates a negative allocation |
| **`discounts`** | Whether a discount is a line, an allocation adjustment, or both; approval thresholds; whether a discount is percentage or absolute |
| **`financial_adjustments`** | ⚠️ **Name collision** with the existing `finance_adjustments` table, which corrects *platform usage cost*. See §6.5. |
| **`receipts`** | Whether a receipt is a document, a number, or a print artefact; whether it is legally required per method |

### 3.2 The phases it blocks

- **Phase 2** — all eight tables, plus the rule at `plan.md:138` requiring append-only history for
  **payment** changes.
- **Phase 8** — `plan.md:256`: *"Track progress and show **estimated, invoiced, and collected** values
  separately"*, and `plan.md:260` requires reconciliation *"without state conflation"*.
- **Phase 9** — the whole phase (`plan.md:262-280`), whose gate is *"Invoice/payment/receipt/balance
  calculations reconcile; partial payments and refunds are idempotent."*
- **Phase 11** — `DENTAL_CLINIC_REQUIREMENTS.md:167-168` requires financial filters by period, dentist,
  service and location, and `:173` requires every amount to drill to source transactions.

---

## 4. What already exists — verified, not assumed

**Citation convention.** `baseline:NNN` = `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql`
(SHA-256 identical to its mirror under `backend/database/src/main/resources/db/migration/`).
`source ¶N` = 1-based paragraph index of `word/document.xml` in
`source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx`, extracted in document order.

| Claim | Evidence |
|---|---|
| **None of the eight clinic-billing tables exists.** A `CREATE TABLE` census across all 27 files in `ops/flyway/sql/` (116 matches) returns none of them. | `ops/flyway/sql/` |
| `reporting-finance` is **the platform's own usage ledger, not patient billing** | `requirements-traceability.md:118-119`; `V20261005090000:28-32` |
| Currency handling today is a **shape check only**: `firstelement_currency_code` is `candidate ~ '^[A-Z]{3}$'`. No FX table, no conversion, no rate date. | `usage_ledger.sql:169-180` |
| …while FR-7 clause 2 already **requires** "currency, **FX source**, period, cost, revenue/billable amount, **tax classification if required**, and adjustment reason" on every amount. So the *pattern* is specified; the clinic implementation does not exist. | `PROJECT_REQUIREMENTS.md:168` |
| ADR-0007 rule 6 already requires: *"Currency and FX are explicit. Every amount carries a currency; conversion records the FX source and rate date."* | `0007:43-44` |
| A **versioned, effective-dated, pinned-rate** money model already exists and is enforced by a database CHECK: `usage_rollup_snapshots` pins both `rateCardVersionId` **and** the `markupRate` value, and `revenueAmount = round(costAmount * markupRate, 6)` is enforced exactly | `V20261005090000:34-52` |
| The existing `finance_adjustments` table corrects **cost of platform usage**, not patient money: *"An adjustment is a reasoned correction to COST, not a revenue override."* | `FinanceController.java:431-443` |
| The finance metric catalogue is completeness-tested over **12 dimensions** — MESSAGE, CONVERSATION, CHANNEL_TRAFFIC, SEAT, STORAGE, API_OPERATION, WORKFLOW_RUN, REPORT_EXPORT, AI_REQUEST, AI_TOKEN, TIME_ENTRY, DOCUMENT_CHUNK. **There is no DOCTOR, SERVICE or PROCEDURE dimension.** | `FinanceMetricCatalogue.java:141`; `UsageDimension.java:25-70` |
| Therefore *"financial summary by doctor/service"* is **structurally absent** and cannot be produced by reusing the ledger | `requirements-traceability.md:149` (row H-4) |
| The only existing price is `procedures.price double precision` — a float with **no currency, no effective date, no version**. It cannot be the basis of an invoice. | `baseline:1185` |
| There is **zero discount and zero refund** in the existing system | `requirements-traceability.md:125` (row F-3) |

### 4.6 Not verified

No live database was inspected. The `requirements-traceability.md` rows are quoted from a verification
performed on 2026-10-12 at commit `f182ada` and were not re-run here.

---

## 5. Options

### 5.1 The primary question: what happens to ADR-0007 rule 7?

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **B1 — Amend and narrow.** Keep "no payment execution, no payment credentials, no gateway" **verbatim**. Add one clause: *recorded* patient billing (invoice, line, allocation, receipt, refund, discount, adjustment) is in scope for the clinic vertical, under FR-7.8's extension point and the clinic requirement. | Both documents stay true; nothing about credential handling changes | Requires a new ADR signed by the ADR-0007 owner, and engineering must not edit `0007` in place | Nothing |
| **B2 — Amend and narrow, with tax and official numbering deferred to a later decision** (a strict subset of B1) | Same amendment, but the v1 scope is invoices-and-records only; tax rate and official numbering are explicitly out of v1 | Leaves the clinic unable to go live if its regulator requires official numbering — a **scheduling** risk, not a data risk | Official/taxed invoicing in v1 |
| **B3 — Billing stays out of scope.** The clinic continues to invoice in its existing system; `plan.md:133` is struck and Phase 9 is deferred entirely | Zero new financial surface; ADR-0007 untouched; the strangler gets smaller | The clinic's Priority 3 (`source ¶227`) is not delivered; Phase 8's "estimated vs invoiced vs collected" collapses to "estimated only" | All eight tables, forever, unless reopened later |
| **B4 — Full billing including gateway / payment execution** | The clinic takes card payments in-product | **Contradicts ADR-0007 rule 7 and `PROJECT_REQUIREMENTS.md:165,174` directly**, and stores payment credentials, which `AGENTS.md:24` forbids. Engineering should not implement this without a new legal basis and a PCI scope decision that no document in this repository provides. | Nothing — but it is the one option no current document authorises |

### 5.2 Riding on the primary choice — still open, still unanswered

| Sub-decision | Options | Engineering note (not a ruling) |
|---|---|---|
| **Invoice timing** | after completion · at booking as a deposit · both (deposit + balance on completion) | `source ¶83` requires **estimated, invoiced and collected to be shown separately**; that separation holds under all three. |
| **Deposits / instalments** | none · single deposit · deposit + instalment schedule | `DENTAL_CLINIC_REQUIREMENTS.md:145` defers instalments explicitly. An instalment schedule is a table `plan.md:133` does **not** list — a gap recorded in §9. |
| **Currencies** | one currency per clinic · multiple currencies with FX · currency-agnostic numeric + display currency | Only the first is cheap. The second inherits ADR-0007 rule 6 and FR-7 clause 2 in full and needs an FX source the repository does not have. |
| **Payment methods** | cash only · cash + card recorded manually · plus transfer · plus gateway | The Arabic source asks for methods but names none. **Recording** that a patient paid by card is not **executing** a card payment — that distinction is what makes B1/B2 possible. |
| **Tax** | none · internal tax classification · enforced tax engine with effective-dated rates | FR-7 clause 2 already requires "tax classification if required", so at minimum a classification column is anticipated. |
| **Official numbering** | internal gap-free sequence · official/statutory sequence · statutory e-invoicing | ⚠️ Only an *issued* number is irreversible. §6.1. |

---

## 6. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Clinic money written into `usage_events` / `rate_cards` | Contaminates FirstElement's own revenue; breaks the `usage_rollup_snapshots` derivation CHECK (`V20261005090000:44-47`) and the F-7 guard (`requirements-traceability.md:129`); "what did we bill the clinic?" becomes unanswerable | **No** — usage events are append-only |
| Billing treated as out of scope when the clinic needs it | Eight tables of Phase 2 never built; Priority 3 not delivered; the strangler cannot move past Phase 8's "invoiced vs collected" | Yes, later, as new work — no data lost, only time |
| Billing treated as in scope when the clinic does not want it | FirstElement becomes the system of record for patient money it has no legal basis to hold | **No** — the data now exists |
| Invoice numbering with gaps, and no rollback rule | Retro-fitting a missing number after a tax authority asks is effectively impossible; a gap is indistinguishable from a cancelled invoice unless the cancel rule is recorded | **No** |
| Tax stored as a computed value rather than a rate + effective date | A rate change silently rewrites history, which ADR-0007 rule 4 forbids for closed periods | **No** |
| Currencies introduced without an FX rate date | Violates ADR-0007 rule 6 and FR-7 clause 2 on day one; every cross-currency total becomes unattributable | **No** for rows already written |
| `financial_adjustments` (clinic) confused with `finance_adjustments` (platform) | A clinic refund silently reconciles against a platform cost correction; reconciliation reports become wrong in a way that is hard to spot | Yes, if caught early |

### 6.5 The name collision is real and must be resolved before Phase 2

`plan.md:133` proposes a **`financial_adjustments`** table. A **`finance_adjustments`** table already exists
(`V20261005090000:976`) and means something different — it corrects platform usage cost
(`FinanceController.java:442-443`). Two tables whose names differ by one letter and whose meanings are
inverted is a defect waiting to happen. **Engineering recommendation:** name the clinic one
`clinic_financial_adjustments` (or better, fold it into `refunds` / `discounts` if the clinic's policy
does not need a third concept). This is a naming recommendation, not a policy decision, and is recorded
in §9 for the orchestrator.

---

## 7. Dependencies and conflicts

### 7.1 The ADR-0007 conflict, restated as an action

**Nothing in this brief amends ADR-0007.** The rule stays as written. The options are:

- **B1/B2** — the orchestrator commissions **a new ADR** that scopes rule 7, citing `0007:45-48`,
  `PROJECT_REQUIREMENTS.md:174`, and `plan.md:276`. ADR-0007 itself is not edited by engineering or by me.
- **B3** — the clinic declines, `plan.md:133` is struck, and `open-decisions.md:132-134` records
  "billing confirmed out of scope".

Until one of those happens, **Phase 2 must not create any of the eight tables.**

### 7.2 ADR-0007 Part 1 rules that transfer unchanged

Rules 1–6 apply to clinic money as much as to usage money and should be inherited rather than reinvented:
every amount drills down (1); rates are versioned and effective-dated (2); no mutable counters are finance
truth (3); closed periods are immutable (4); zero is not "no data" (5); currency and FX are explicit (6).
The existing DDL already enforces (1)–(5) for usage (`V20261005090000:34-60`); the clinic tables must
carry the same discipline, not a looser copy.

### 7.3 ADR-0007 Part 2 — billing is a **consequential** AI action

`0007:70` places *"financial or billing change"* in the consequential class: human approval required, the
model may only request it. No AI path may create an invoice, record a payment, or apply a discount.
`AGENTS.md:16` adds that software does not approve treatment or payment.

### 7.4 DEC-003 — the cancellation fee

A cancellation fee is a billing event. If DEC-003 records a fee and DEC-004 resolves to B3, the fee column
is permanently null and must be documented as *"no fee capability"* rather than *"no fee"*.

### 7.5 DEC-008 — who is the invoicing entity?

ADR-0018 / DEC-008 make a clinic a `Project`, and `ClinicLocation` is not introduced
(`open-decisions.md:30-31`). An **official invoice number belongs to a legal entity**, not to a CRM project
row. The clinic's legal name, tax identifier and address therefore have nowhere to live in the current
model. **This is a genuine gap, not a preference**, and it is the strongest argument for B2 (defer official
numbering) over B1-with-official-numbering-in-v1. Recorded in §9.

### 7.6 DEC-014 — approval thresholds

`plan.md:272` requires *"delegated thresholds and approvals for discounts/refunds/adjustments"*. DEC-014's
configurable per-clinic roles supply the actor; the **threshold values** are a clinic finance decision and
are not in `plan.md:133`'s table list. Gap recorded in §9.

---

## 8. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| The eight tables not yet created | **Yes** | Nothing |
| Columns added and left null | **Yes** | Additive |
| A **recorded** payment row | **No** once written | Append-only by `plan.md:138`; correcting it means an adjustment, not an edit |
| An **issued invoice number** | **No** | A number shown to a third party is a fact in the world. Re-numbering is a legal act, not a migration. |
| A closed accounting period | **No** | ADR-0007 rule 4: only via an approved, audited correction linked to source events |
| A refund | **No** | Money has moved |
| ADR-0007 rule 7 itself | **Yes** | An ADR can be superseded — which is precisely why it must be a *new* ADR, not an edit |

---

## 9. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| 1 | **Does clinic billing amend ADR-0007 rule 7 (B1/B2), or does billing stay out of scope (B3)?** | |
| 2 | Invoice timing: after treatment, at booking as a deposit, or both? | |
| 3 | Deposits? Instalments? If instalments, is a schedule table needed (not in `plan.md:133`)? | |
| 4 | Currencies: one per clinic, or multiple with FX? | |
| 5 | Payment methods accepted, and does FirstElement **record** them or **execute** them? | |
| 6 | Tax: none, classification only, or an enforced engine? | |
| 7 | Official invoice numbering: none, internal sequence, or statutory/e-invoicing? | |
| 8 | If official numbering — what is the clinic's **legal entity** name, tax identifier and address, given DEC-008? | |
| 9 | Approval thresholds for discount / refund / adjustment, and who may approve each? | |
| 10 | Is a receipt required per method, or only on request (`source ¶81`: «عند الحاجة»)? | |
| 11 | Naming: accept `financial_adjustments` (collides with the existing `finance_adjustments`) or rename? | |

Approver, date, and whether each answer is reversible: ______________________

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007) that either **scopes ADR-0007 rule 7** or **records billing as out of scope**.