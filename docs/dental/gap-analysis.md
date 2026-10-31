# Gap Analysis — Dental Clinic Module

**Date:** 2026-10-20  
**Phase:** Phase A — Discovery  
**Owner:** Adam (First Element LLC)  
**Target Market:** Dental Clinics in Jordan  
**Reference Document:** `docs/dental/PLAN.md` (§3)  

---

## 1. Executive Summary

The platform has established a substantial, robust foundation with hand-written JDBC SQL, multi-tenant composite foreign keys, clean Spring Boot architecture, Arabic-first RTL Angular frontend, and a governed AI-RAG module. 

Recent work implemented the database foundations and first-class read/write surfaces for:
- Clinic Patients (`/api/v1/clinic/patients`)
- Odontogram (`/api/v1/clinic/patients/{id}/odontogram`)
- Schedules & Overlap Constraints (`/api/v1/clinic/schedules`)
- Treatment Plans (`/api/v1/clinic/patients/{id}/treatment-plans`)
- Clinical Encounters & Prescriptions (`/api/v1/clinic/patients/{id}/encounters`)
- Invoices & Payments (`/api/v1/clinic/invoices`, `/api/v1/clinic/payments`)
- Cancellation Policies (`/api/v1/clinic/settings/policies`)
- Reports (`/api/v1/clinic/reports`)
- AI Assistant Runs (`/api/v1/clinic/ai/runs`)

However, to satisfy the complete requirements of `docs/dental/PLAN.md` (Slices S1 through S9) for professional dental clinic operations in Jordan, specific architectural and functional gaps must be addressed in subsequent slices.

---

## 2. Capability Gap Matrix (10 Core Areas)

| # | Capability | Status | Evidence / Existing Implementation | Gaps to Address in Slices | Safest Extension Point |
|---|---|:---:|---|---|---|
| **1** | **Patients, family/guardian, consent** | **Partial** | Tables: `patients`, `patient_identities` (`V20261013000000`).<br>Code: `ClinicPatientController.java`, `clinic-patients.component.ts`.<br>Duplicate detection: `GET /api/v1/clinic/patients/duplicates`. | • Family/guarantor links (`patient_relationships`).<br>• Formal consent logs (`patient_consents`, disclosure log).<br>• Reversible patient merge workflow. | Migration for `patient_relationships` and `patient_consents`; add Family & Consent tabs to `ClinicPatientDetailComponent`. (Slice S1) |
| **2** | **Clinic resources (providers, operatories, calendars)** | **Exists** | Tables: `doctors`, `clinic_resources` (chairs/rooms), `clinic_schedules`, `clinic_time_off` (`V20261016000000`), overlap constraints (`V20261017000000`).<br>Code: `ClinicScheduleRepository.java`, `clinic-schedules.component.ts`. | • Dedicated operatory/chair view mode on calendar.<br>• Buffer times between procedures enforcement. | Add operatory column toggle in calendar; procedure buffer minutes already exist in `procedures.bufferMinutes`. (Slice S2) |
| **3** | **Appointments, holds, waitlist, no-show, check-in, recalls, reminders** | **Partial** | Tables: `appointments` (`V20261019000000` with `AppointmentStatus`, cancellation reasons), `clinic_cancellation_policies` (`V20261023000000`).<br>Code: `ClinicController.java`, `clinic-calendar.component.ts`. | • Temporary appointment holds with TTL expiry.<br>• Waitlist / ASAP queue (`appointment_waitlist`).<br>• Patient arrival/check-in tracking queue.<br>• Automated reminders via WhatsApp/Telegram channels. | Migration adding `heldUntil` to appointments and `appointment_waitlist`; connect reminder dispatcher to `worker-runtime`. (Slice S2) |
| **4** | **Encounters, medical history, notes, signatures, amendments** | **Partial** | Tables: `clinical_encounters`, `clinical_prescriptions` (`V20261021000000`).<br>Code: `EncounterService.java`, `ClinicEncounterController.java`, `clinic-encounters.component.ts`. | • Structured medical history (allergies, chronic conditions, vitals).<br>• Immutable signed notes: prevent direct edit once signed; require append-only amendments (`encounter_amendments`). | Add `patient_medical_history` and `encounter_amendments` tables; lock encounter upon SIGNED state. (Slice S3) |
| **5** | **Odontogram (FDI) and periodontal charting** | **Partial** | Tables: `patient_odontogram_findings`, `clinic_finding_vocabulary_template` (`V20261014000000`, `V20261018000000`).<br>Code: `OdontogramService.java`, `clinic-odontogram.component.ts`. FDI 11-85 & surfaces M/O/D/B/L/I supported. | • Periodontal charting (6-site probing depths, BOP, plaque, gingival margin, furcation, derived CAL).<br>• Separate views for: Status vs Plan vs Completed. | Create `patient_perio_charting` table with 6 sites per tooth; add Perio mode to `ClinicOdontogramComponent`. (Slice S4) |
| **6** | **Treatment plans and procedure catalog** | **Partial** | Tables: `procedures`, `treatment_plans`, `treatment_plan_items` (`V20261020000000`).<br>Code: `TreatmentPlanService.java`, `clinic-treatment-plans.component.ts`. Statuses: DRAFT, PROPOSED, ACCEPTED, IN_PROGRESS, COMPLETED. | • Alternative plan options (Option A vs Option B).<br>• Patient formal approval signature capture.<br>• Immutable version snapshots on approval. | Add `planOptionGroup` to `treatment_plans`; create immutable version snapshot when status transitions to ACCEPTED. (Slice S5) |
| **7** | **Invoices, ledger, payments, refunds, statements** | **Partial** | Tables: `clinic_invoices`, `clinic_invoice_items`, `clinic_payments` (`V20261022000000`).<br>Code: `InvoiceService.java`, `ClinicBillingController.java`, `clinic-invoices.component.ts`, `clinic-payments.component.ts`.<br>Precision: `NUMERIC(15, 3)` (JOD fils). | • Refunds and payment reversals.<br>• Dual-entry append-only patient ledger view.<br>• Patient printable statement of account. | Add `clinic_refunds` table; integrate with `reporting-finance` usage ledger and generate printable statements. (Slice S6) |
| **8** | **Documents, radiographs, images, PDFs** | **Partial** | Tables: `patient_attachments` (`V20261013000000`) with retention class & legal hold.<br>Storage Engine: `FilesystemExportObjectStore` (path traversal guard, atomic commit). | • Clinical media upload/download HTTP endpoints.<br>• Short-lived signed download URLs.<br>• MIME/size allowlists and SHA-256 integrity checks.<br>• UI media gallery on patient record. | Build `ClinicMediaService` in `crm-core` wrapping `patient_attachments` and object storage; add Media gallery tab in patient details. (Slice S7) |
| **9** | **Audit, break-glass, curated reports** | **Partial** | Tables: `audit_logs`, `admin_audit_events`.<br>Code: `ClinicReportService.java`, `clinic-reports.component.ts` (Revenue, Doctor workload, Procedures demand, Attendance rates). | • Break-glass emergency access workflow (reason prompt, temporary elevated grant, instant alert, audit stream).<br>• Comprehensive patient read-audit logging. | Implement `BreakGlassService` with `clinic_break_glass_sessions` table and security filter; log patient views. (Slice S8) |
| **10** | **AI assistant (summaries, recall prioritization, FAQ)** | **Partial** | Module: `:backend:ai-rag` (Spring AI, pgvector embeddings, chunking, tool registry, token budgets, `MockAiClient`).<br>Traceability: `ai_runs`, `ai_approvals`.<br>Code: `ClinicAiService.java`, `clinic-ai-assistant.component.ts`. | • Dental-specific prompt templates.<br>• Domain tools: Encounter summary draft, recall prioritization, documentation gap detector, payment allocation draft.<br>• Dedicated AI Kill-Switch toggle. | Register domain dental tools in `AiToolRegistry`; enforce human-in-the-loop review for all drafts. (Slice S9) |

---

## 3. Recommended Slice Execution Roadmap (S1 → S9)

Following §0 and §4 of `docs/dental/PLAN.md`, implementation will proceed in strict order after owner approval:

1. **Slice S1 — Patients, Family, and Consent:**
   - Add `patient_relationships` (parent, guardian, guarantor, spouse) and `patient_consents` (treatment consent, HIPAA/privacy, disclosure log).
   - Implement reversible patient merge service with audit trail.
   - Update `clinic-patient-detail.component.ts` with Family & Consent management tabs.

2. **Slice S2 — Scheduling Hardening, Holds, and Waitlist:**
   - Add appointment holds with automatic TTL expiration.
   - Implement waitlist / ASAP queue (`appointment_waitlist`).
   - Wire automated appointment reminders to existing WhatsApp / Telegram connectors via `worker-runtime`.

3. **Slice S3 — Encounters, Structured History, and Immutable Notes:**
   - Add structured medical history: allergies, chronic systemic conditions, vitals.
   - Lock clinical encounters upon signing (`status = 'SIGNED'`); implement `encounter_amendments` table for append-only amendments.

4. **Slice S4 — Periodontal Charting & Odontogram Enhancements:**
   - Add 6-site periodontal measurement storage (probing depth, bleeding, plaque, gingival margin, furcation, derived CAL).
   - Add toggle for: Current Status vs Planned vs Completed views in `clinic-odontogram.component.ts`.

5. **Slice S5 — Alternative Treatment Plans & Approved Immutability:**
   - Support multiple plan options (Option A / Option B) under a single treatment case.
   - Implement patient acceptance workflow and lock approved plan versions.

6. **Slice S6 — Refunds, Ledger Integration & Statements:**
   - Add refunds and payment reversal transactions with strict audit reasons.
   - Provide consolidated patient ledger account view and printable account statements in 3-decimal JOD.

7. **Slice S7 — Clinical Documents & Radiographs:**
   - Implement secure media upload/download endpoints with MIME validation, SHA-256 checks, and signed expiring URLs.
   - Add attachment gallery in Angular patient view.

8. **Slice S8 — Break-Glass Emergency Access & Audit Hardening:**
   - Implement break-glass protocol with mandatory reason recording, session expiration, and immediate notification.
   - Ensure all patient record reads produce immutable audit events.

9. **Slice S9 — Governed Dental AI Tools & Kill-Switch:**
   - Register dental clinical tools in `AiToolRegistry` (encounter drafting, recall prioritization, missing data alerts).
   - Add emergency AI kill switch and ensure all clinical outputs strictly remain draft-only pending human approval.

---

## 4. Phase A Completion & Verification

- Both `docs/dental/repository-inventory.md` and `docs/dental/gap-analysis.md` have been generated based on direct inspection of the repository.
- **Zero application code was altered** during Phase A.
- All conventions (hand-written JDBC SQL, PostgreSQL pgvector, 3-decimal JOD currency, DEC-007 retention, DEC-009 FDI numbering, and Angular design-system invariants) are preserved.

> **Status:** Phase A (Discovery) is COMPLETE. Awaiting owner review and explicit authorization to proceed with **Slice S1 (Patients, Family, Consent)**.
