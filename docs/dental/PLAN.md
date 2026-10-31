# Dental Clinic Module — Implementation Plan for Antigravity

**Purpose:** Add the dental-clinic feature set to the **existing** platform. This is an *extend-and-complete* plan, not a rebuild.
**Source:** Adapted from the OpenCode bundle (`plan.md`, `PR.md`, `AGENTS.md`, subagent files). The research and domain model are kept; the greenfield assumptions are removed.
**Owner:** Adam (First Element LLC). **Target market:** clinics in Jordan.

---

## 0. How to use this file

1. Put this file in the repository root as `docs/dental/PLAN.md` and tell the agent to follow it.
2. Run **Phase A (discovery)** first. It changes no application code and ends with a gap analysis for the owner to review.
3. Only after the owner approves the gap analysis, implement slices **S1 → S9** in order, one vertical slice at a time.
4. After each slice: run tests, write the handoff report (Section 7), and stop for review.

---

## 1. Context (owner-stated — verify every point against the code)

- The platform already exists: **Angular** frontend, **Spring Boot** backend, **PostgreSQL** (with pgvector), ready UI, portal, and database, plus existing **finance, dashboard, and users** modules.
- It is a **multi-tenant** system (organization → project), **Arabic-first RTL** with English, built as an AI-native omnichannel CRM/helpdesk (WhatsApp, Instagram, Telegram). The clinic front desk is the first concrete domain.
- Data access uses **hand-written JDBC SQL**. It was built as a strangler migration from a frozen legacy NestJS/React stack.
- The **AI agent** is in Spring Boot and roughly 40% complete. It currently runs on a local deterministic adapter and is **draft-only** (a human presses send). Remaining work: system prompt, knowledge base, LLM adapter, channel APIs.
- Decisions already made: **DEC-007** — 10-year retention floor from the last procedure for Jordan tenants. **DEC-009** (in progress) — odontogram finding vocabulary using FDI tooth numbering.

If the code contradicts anything above, **the code wins**. Record the discrepancy in the gap analysis.

---

## 2. Hard constraints

### Follow the existing system
- **Do not rewrite or replace** existing modules. Extend them. Reuse the existing finance, users, auth, audit, and dashboard code wherever it fits.
- **Match existing conventions**: JDBC SQL style, package layout, migration tool, error format, test setup, Angular structure, i18n approach.
- Do **not** introduce JPA, jOOQ, a new framework, or a new tenancy model without an ADR in `docs/adr/` and owner approval. (The original plan suggested JPA/jOOQ; this project uses JDBC, so adapt: optimistic locking is a `version` column checked in `UPDATE … WHERE id=? AND version=?`.)
- The plan's `clinic_id` must map to the **existing tenant key** (organization/project). Decide in an ADR whether a clinic is a project or a child entity. Do not build a parallel scope model.

### Security and privacy (non-negotiable)
- The **server is the authorization authority**. Angular guards are UX only.
- Enforce tenant, clinic, provider, patient, and consent scope in services, queries, jobs, exports, file access, reports, and AI tools. PostgreSQL RLS is defense in depth only.
- Never store PHI in `localStorage`, ordinary IndexedDB, or unencrypted caches. Never log PHI or secrets.
- Never expose DB rows or internal entities directly; use DTOs with validation.
- No fixed credentials, plaintext or weak hashes, public attachment URLs, or arbitrary SQL for ordinary users.
- Never read production secrets or live patient data. Use `.env.example` and synthetic fixtures only.

### Data rules
- Timestamps in UTC (`timestamptz`) with an explicit clinic time zone. (Jordan is, as far as I know, on a fixed UTC+3 with no DST — verify — but keep code tz-aware and test with a DST time zone.)
- Money as `numeric` plus currency. **JOD has 3 decimal places (fils)** — never assume 2.
- Clinical and financial records are **append-only with amendment/reversal history**: author, reason, timestamp, provenance. Signed clinical entries are never overwritten.
- Use idempotency keys for payments, imports, and callbacks. Use state machines for appointment, plan, invoice, and payment status.
- Retention: apply DEC-007 (10 years from last procedure) in the retention and deletion logic.

### AI rules (match the current draft-only design)
- AI is assistive and human-reviewed. **No** autonomous diagnosis, treatment selection, prescriptions, silent chart writes, or payment/refund changes.
- AI tools call domain services, never SQL, and re-check tenant/patient/consent at execution time.

### Licensing
- This is a **clean-room** build. Do not copy code, schemas, or UI from DentneD, RMDC, Open Dental, DentalPin, Dentrix, Curve, CareStack, or others. They are capability references only.
- Every new dependency, font, SVG asset, and terminology set must have its license recorded. **Stop and ask** on any GPL/MPL/BSL/proprietary or terminology-licensing question.

---

## 3. Phase A — Discovery (no application code changes)

Output: `docs/dental/repository-inventory.md` and `docs/dental/gap-analysis.md`.

**Inventory:** branch and commit; modules and build/test commands; migration tool and current schema; auth and tenancy implementation; audit mechanism; file storage; existing finance model; existing AI module state; CI; dependencies and licenses; known defects; risks.

**Gap analysis:** for every row below, mark **Exists / Partial / Missing**, cite file paths, and name the safest extension point.

| # | Capability | Status | Evidence / extension point |
|---|---|---|---|
| 1 | Patients, family/guardian, consent |  |  |
| 2 | Clinic resources (providers, operatories, calendars) |  |  |
| 3 | Appointments, holds, waitlist, no-show, check-in, recalls, reminders |  |  |
| 4 | Encounters, medical history, notes, signatures, amendments |  |  |
| 5 | Odontogram (FDI) and periodontal charting |  |  |
| 6 | Treatment plans and procedure catalog |  |  |
| 7 | Invoices, ledger, payments, refunds, statements |  |  |
| 8 | Documents, radiographs, images, PDFs |  |  |
| 9 | Audit, break-glass, curated reports |  |  |
| 10 | AI assistant (summaries, recall prioritization, FAQ) |  |  |

**Stop after Phase A.** Wait for owner approval before any implementation.

---

## 4. Implementation slices

Implement in order. Each slice is vertical: DB → backend → API → Angular UI → tests. Skip any part the gap analysis shows already exists, and extend rather than duplicate.

### S1 — Patients, family, consent
- **Scope:** patient identity and demographics, contacts, guardians, family/guarantor links, duplicate detection, merge workflow with audit, archive/restore, consent records and disclosure log.
- **Backend:** application service with commands; DTOs; validation; optimistic locking; audit on every read and write of patient data.
- **UI:** patient workspace shell with RTL/LTR, keyboard-friendly forms, explicit save/conflict states.
- **Accept:** wrong-tenant and wrong-clinic access returns 403/404 in tests; merge is reversible from audit; consent changes are append-only.

### S2 — Clinic resources and scheduling
- **Scope:** providers, operatories/chairs, working hours, holidays, appointment types and durations, buffers, holds, waitlist/ASAP list, reschedule, no-show, check-in, recalls, reminders. Reminders should reuse the existing messaging channels (WhatsApp/Telegram) via the existing channel layer.
- **DB:** **conflict protection at the database level** (PostgreSQL exclusion constraint on provider + time range, and on room + time range), not only in application code.
- **UI:** schedule board (day/week, per provider/operatory), drag-to-reschedule with conflict feedback.
- **Accept:** concurrent double-booking test fails safely (one wins, one gets a clear conflict error); time-zone and DST tests pass; holds expire correctly; reminders are idempotent.

### S3 — Encounters, history, clinical notes
- **Scope:** encounters/visits, complaints, allergies, conditions, medications, vitals, structured notes, signing, amendments, provenance.
- **Accept:** a signed note cannot be edited — only amended with author, reason, and a visible history; amendments are covered by tests.

### S4 — Odontogram and periodontics
- **Scope:** canonical **FDI/ISO 3950** storage with display conversion (Universal/Palmer) at the UI boundary only. Per-surface findings as independent, timestamped, authored records. States: not-charted / absent / normal / abnormal / historical / planned / completed / unknown. Separate **status**, **plan**, and **completed** views. Restorations, implants, missing/extracted, caries, endodontics, diagnoses. Perio: six-site measurements with units and method, **derived CAL** computed consistently.
- **Vocabulary:** use the vocabulary from **DEC-009**. Store terminology system, code, display, and version.
- **Do not** model the chart as `t11…t48` columns.
- **UI:** per-patient/per-exam state store (Signals or RxJS) — no singleton chart engine shared across patients. Safe, allowlisted SVG rendering. Keyboard-first charting; accessible; RTL.
- **Accept:** notation round-trip tests; CAL calculation tests; history preserved on correction; SVG injection test fails safely.

### S5 — Treatment plans and procedure catalog
- **Scope:** procedure catalog with fees; versioned plans; alternative options; patient approval/consent; estimates; multi-visit procedures; status transitions; outcomes; cancellation.
- **Accept:** plan edits create a new version; approved versions are immutable; state-machine tests cover illegal transitions.

### S6 — Billing and ledger (integrate, don't duplicate)
- **Scope:** generate invoices from approved plan items and completed procedures using the **existing finance module**. Payments, refunds, allocations, statements, receipts, reversals, reconciliation. **Append-only ledger.**
- **Money:** 3-decimal JOD; discounts and tax rules per the existing finance model.
- **Accept:** duplicate payment submission is idempotent; refunds and reversals create new ledger entries; ledger totals reconcile with invoices in tests; no card numbers are stored (use a hosted/tokenized provider if card payments are added).

### S7 — Documents and media
- **Scope:** private object storage for radiographs, photos, PDFs, forms. Opaque keys, short-lived signed URLs, MIME/size allowlists, malware scanning, checksums, encryption at rest, retention and legal hold (DEC-007), access audit.
- **Accept:** malicious/polyglot file tests; expired-URL test; scan-outage test (uploads held, not accepted); DB-plus-media restore test.

### S8 — Audit, reporting, access control hardening
- **Scope:** immutable/tamper-evident audit for reads, writes, exports, downloads, and permission changes. Break-glass access (reason, expiry, alert, review). **Curated, parameterized, tenant-scoped reports** (no arbitrary SQL). RBAC plus clinic/provider/patient scope, method-level authorization.
- **Accept:** IDOR and role-escalation negative tests; break-glass is logged and expires; reports cannot cross tenants.

### S9 — Finish the AI assistant (governed)
- **Scope:** complete the remaining 40%: system prompt, knowledge base (approved clinic content only), LLM adapter behind a **provider/model registry**, channel APIs. Allowed use cases: visit-summary and message **drafts**, recall/no-show prioritization with transparent factors, FAQ and scheduling assistance, missing-field detection, payment-matching **proposals**.
- **Controls:** typed tools over authorized services; PHI minimization/redaction before any cloud model; token and cost budgets; timeouts; circuit breaker; **kill switch**; full provenance audit (model, version, prompt template, sources, tool calls, output, approver); outputs stay drafts until a human approves.
- **Accept:** prompt-injection, cross-tenant tool-call, identifier-leakage, budget-exhaustion, and kill-switch tests pass; evaluation fixtures use synthetic data only.

### Deferred — needs an owner decision before any work
Do **not** build these without explicit approval and an ADR on applicability for Jordan:
- Insurance eligibility/claims (ADA/X12) and clearinghouse adapters
- FHIR R4 export/adapters
- e-prescribing
- DICOM / PACS integration
- Inventory, suppliers, lab cases
- Patient self-booking portal expansion
- Local e-invoicing/tax integration (check whether Jordan's e-invoicing requirements apply to clinics) and local data-protection compliance — **needs legal review; not a compliance certification**

---

## 5. Validation gates (after every slice)

1. Run the **narrowest relevant tests first**, then the full applicable suite.
2. Required evidence per slice: unit and integration tests (Testcontainers/Postgres if the repo uses them); migration up **and** rollback tested on an empty DB and a populated synthetic DB; authorization negative tests (wrong tenant/clinic/patient, IDOR, role escalation); concurrency tests where relevant; API contract/OpenAPI diff if the repo has one; Angular type-check, component tests, and an accessibility check for new UI.
3. Run dependency/license and secret scans if the repo already has them; if not, record that in the report as a gap rather than adding tooling silently.
4. **Do not claim completion without evidence** (command output, test results).

---

## 6. Stop conditions

Stop and report — do not guess — when: the repository state contradicts this plan in a way that changes the design; a migration is destructive without a rollback; tenant isolation is unproven; a critical security finding appears; a license or terminology question is unresolved; a connector needs credentials you don't have; or a test cannot be run.

Before any migration or broad refactor, **create a checkpoint** (branch/commit) and note the restore path.

---

## 7. Handoff report (after every slice)

Write to `docs/dental/reports/<slice>.md`:

```
Summary:
Changed files:
Tests/commands executed:
Pass/fail result:
Database or API contract changes:
Security/privacy impact:
Migration/rollback impact:
License/dependency impact:
Known limitations:
Next steps:
```

---

## 8. Optional: parallel work in Antigravity

If you split work across several agents, give each **exclusive file ownership** and never let two agents edit the same files.

| Workstream | Owns | Depends on |
|---|---|---|
| Discovery & ADRs | `docs/dental/`, `docs/adr/` | — (runs first) |
| Database | migrations, seed data, DB tests | ADRs |
| Backend | services, DTOs, controllers, API tests | migrations merged |
| Frontend | Angular features, API client, UI tests | backend API contract stable |
| Security review | review reports, negative tests | slice complete |
| AI | AI module, registry, eval fixtures | S1–S8 authorization in place |

Sequencing rules: migrations before backend integration tests; backend contract before generated Angular clients; authorization before AI tools; all implementation before the security review of that slice. Run an adversarial review (try IDOR, tenant bypass, race conditions, malicious uploads, rollback failure, PHI leakage, prompt injection) after each slice and write findings under `docs/reviews/` **without silently fixing them**.

---

## 9. First prompt to paste into Antigravity

> Read `docs/dental/PLAN.md` fully. Then perform **Phase A only**: inspect the actual repository and write `docs/dental/repository-inventory.md` and `docs/dental/gap-analysis.md` exactly as specified. Do not modify application code. Follow the existing conventions (JDBC SQL, current tenancy model). When finished, summarize the gaps and wait for my approval before starting slice S1.
