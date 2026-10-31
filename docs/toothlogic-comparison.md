# ToothLogic Benchmark & Comparison Document

**Document Version:** 1.0.0  
**Target Platform:** FirstElement Clinical OS (Dental Practice Platform)  
**Implementation Architecture:** Angular 22 Standalone Frontend (`frontend/`) + Spring Boot 3.5.6 Multi-Module Backend (`backend/`) + PostgreSQL 16 (pgvector) + Redis 7  
**Benchmark Reference:** [ToothLogic AI Public Product Profile](https://toothlogic.ai/#key) (All-in-one operations dashboard, schedule & chair management, patient 360, clinical documentation/odontogram, lab case pipeline, billing/payouts, multi-clinic readiness, role-based access, and assistive AI receptionist).

---

## 1. Architectural Alignment & Boundaries

| Aspect | ToothLogic Benchmark Promise | Current FirstElement Stack | Gap / Action Required |
| :--- | :--- | :--- | :--- |
| **All-in-one Dashboard** | Real-time operations KPIs, live schedule, urgent attention queue, booking funnel, procedure mix, lab status. | Single dashboard exists but mixes generic CRM and initial metrics. | Refactor dashboard to ToothLogic "Command Center" layout with authoritative server-derived KPIs (Phase 2). |
| **Appointments & Scheduling** | Multi-view calendar (Day/Week/Month/List), doctor & chair/operatory filtering, server conflict validation. | Calendar, appointments list, and schedules exist (`ClinicCalendarComponent`, `ClinicSchedulesComponent`). | Add operatory/chair view, double-booking prevention constraint, and inline patient creation (Phase 3). |
| **Patient 360** | Complete patient history, allergies/alerts chips, FDI odontogram, treatment plans, radiographs, ledger. | Patient list, detail, and FDI odontogram built across S1-S7 slices. | Add sticky patient header, allergy badges, and direct clinical timeline drawer (Phase 4). |
| **Clinical Documentation** | Step-by-step clinical flow: Check-in → Encounter → Odontogram → Plan → Procedure → Invoice. | Encounters, notes, and FDI tooth charting implemented in S3, S4, S5. | Unify into guided clinical workflow with explicit amendment tracking and signing state (Phase 5). |
| **Doctor & Chair Management** | Working hours, chairs/rooms, capability matrix, multi-branch timezones. | Working hours and doctors implemented; resources/chairs partially mapped. | Solidify operatory allocation, doctor workload cards, and branch price lists (Phase 6). |
| **Dental Lab Pipeline** | 7-stage case tracking (*Draft → Sent → Accepted → In Production → Received → Delivered → Closed*). | Initial lab tracking noted in schema/specs; lacking dedicated pipeline UI. | Build first-class Kanban/pipeline board with due/overdue alerts and shade/prescription fields (Phase 7). |
| **Billing & Payouts** | Plan-to-invoice generation, partial payments, receipts, clinician commission payouts, immutable ledger. | Invoices, payments, and refunds implemented in S6 (`ClinicInvoicesComponent`, `ClinicPaymentsComponent`). | Connect treatment plan lines directly to invoice items, add receipt preview, and enforce clinician commission ledger (Phase 8). |
| **AI Receptionist** | Omnichannel dental intents (book, reschedule, price, FAQ), live availability check, draft-only human review. | AI assistant implemented in S9 (`ClinicAiAssistantComponent`), backed by Spring RAG + local adapter. | Wire live availability tool, atomic escalation seam, and safety guardrails (Phase 9). |
| **Analytics & Reports** | Decision-oriented reports: chair utilization, doctor productivity, recall retention, procedure demand, financial collections. | Reporting modules and export catalog implemented (`ClinicReportsComponent`, `FinanceComponent`). | Surface real drill-down provenance, server-side date filtering, and KPI definitions (Phase 10). |

---

## 2. Route-to-Feature Matrix

| Public Path | Component | Status | Target ToothLogic Feature Group |
| :--- | :--- | :--- | :--- |
| `/app/dashboard` | `DashboardComponent` | **Live** | Clinic Operations Command Center |
| `/app/clinic/calendar` | `ClinicCalendarComponent` | **Live** | Multi-Doctor Schedule & Operatory Agenda |
| `/app/clinic/appointments/:id` | `ClinicAppointmentDetailComponent` | **Live** | Appointment Card, Status & Check-In Action |
| `/app/clinic/patients` | `ClinicPatientsComponent` | **Live** | Patient Registry & Duplicate Detection |
| `/app/clinic/patients/:id` | `ClinicPatientDetailComponent` | **Live** | Patient 360 Hub with Sticky Context Rail |
| `/app/clinic/patients/:id/odontogram` | `ClinicOdontogramComponent` | **Live** | Interactive FDI Dental Charting |
| `/app/clinic/patients/:id/treatment-plans` | `ClinicTreatmentPlansComponent`| **Live** | Multi-Phase Treatment Planning & Acceptance |
| `/app/clinic/patients/:id/encounters` | `ClinicEncountersComponent` | **Live** | Clinical SOAP Notes & History Amendments |
| `/app/clinic/doctors-and-schedules` | `ClinicSchedulesComponent` | **Live** | Clinician Working Hours & Shift Roster |
| `/app/clinic/services-and-resources` | `ClinicServicesComponent` | **Live** | Dental Procedure Catalog & Pricing |
| `/app/clinic/labs` | `ClinicLabsComponent` | **To Add** | Dental Lab Case Pipeline & Tracking |
| `/app/clinic/invoices` | `ClinicInvoicesComponent` | **Live** | Dental Invoicing, Statements & Deductions |
| `/app/clinic/payments` | `ClinicPaymentsComponent` | **Live** | Payment Processing, Receipts & Ledger |
| `/app/clinic/reports` | `ClinicReportsComponent` | **Live** | Practice Insights, Recall, & Utilization |
| `/app/clinic/ai-assistant` | `ClinicAiAssistantComponent` | **Live** | Governed AI Receptionist & Intent Analyzer |
| `/app/inbox` | `InboxComponent` | **Live** | Omnichannel Patient Messaging & Escalations |

---

## 3. Data & API Ownership Map

| Domain Aggregate | Spring Boot Module (`backend/`) | Database Schema & Key Tables | Angular Service / Client |
| :--- | :--- | :--- | :--- |
| **Identity & Access** | `:backend:identity-access` | `users`, `sessions`, `user_roles` | `SessionService`, `SessionStore` |
| **Tenancy & Clinics** | `:backend:tenant-security` | `organizations`, `projects` (clinics) | `ProjectContextService` |
| **Patients & Families**| `:backend:crm-core` | `customers`, `clinic_patients`, `clinic_patient_family_links`, `clinic_patient_consents` | `ClinicPatientService` |
| **Appointments & Sched**| `:backend:crm-core` | `appointments`, `clinic_schedules`, `clinic_time_off`, `clinic_resources` | `ClinicAppointmentService` |
| **Odontogram & Charting**| `:backend:crm-core` | `clinic_odontogram_findings`, `clinic_periodontal_probings` | `ClinicOdontogramService` |
| **Treatment Plans** | `:backend:crm-core` | `clinic_treatment_plans`, `clinic_treatment_plan_phases`, `clinic_treatment_plan_items` | `ClinicTreatmentPlanService` |
| **Encounters & Notes** | `:backend:crm-core` | `clinic_encounters`, `clinic_encounter_notes`, `clinic_medical_history` | `ClinicEncounterService` |
| **Billing & Payments** | `:backend:reporting-finance` | `clinic_invoices`, `clinic_invoice_items`, `clinic_payments`, `clinic_refunds`, `clinic_ledger` | `ClinicInvoiceService` |
| **Labs Pipeline** | `:backend:crm-core` | `clinic_lab_cases`, `clinic_lab_timeline`, `clinic_lab_vendors` | `ClinicLabService` |
| **AI Assistant** | `:backend:ai-rag` | `ai_runs`, `ai_agent_versions`, `clinic_knowledge_docs` | `ClinicAiService` |
| **Audit & Governance** | `:backend:administration` | `audit_logs`, `security_events`, `clinic_break_glass_access` | `AuditService` |

---

## 4. Phase-by-Phase Execution Checklist (0 → 10)

- [x] **Phase 0 — Baseline and inventory**
  - Verify local stack runtime (Spring Boot 4000/4101 + Angular 4200 + Postgres 5432 + Redis 6379).
  - Document comparison against ToothLogic benchmark.
- [ ] **Phase 1 — Shell & Design System Enhancement**
  - Implement clinical command-center layout in Angular shell.
  - Reorganize navigation into Today, Patient, and Management modes.
  - Ensure deep teal (`#0a7c8c`), light clinical background (`#f4f8fa`), and 1px crisp borders.
  - Ensure zero placeholder/dead links in sidebar.
- [ ] **Phase 2 — Unified Operations Dashboard**
  - Connect real KPI calculations (appointments, confirm rate, revenue, open balance, lab status).
  - Add 5-step booking funnel and operational attention strip.
- [ ] **Phase 3 — Appointments & Calendar Hardening**
  - Authoritative double-booking rejection and chair/doctor schedule views.
- [ ] **Phase 4 — Patient 360 Workspace**
  - Sticky identity bar with alerts/allergies, upcoming appointment, and balance due.
- [ ] **Phase 5 — Clinical Guided Workflow**
  - End-to-end flow: Encounter → Odontogram → Plan → Completed procedure → Invoice.
- [ ] **Phase 6 — Doctors, Services & Operatories**
  - Capacity matrix, procedure catalog with duration and pricing.
- [ ] **Phase 7 — Dental Lab Pipeline**
  - 7-stage case tracking board with overdue reminders.
- [ ] **Phase 8 — Billing, Payments & Statements**
  - Plan-to-invoice generation, receipt preview, and clinician payout reconciliation.
- [ ] **Phase 9 — AI Receptionist & Smart Triage**
  - Live availability tool, dental FAQ RAG, atomic human escalation seam.
- [ ] **Phase 10 — Clinic Reports & Insights**
  - Doctor productivity, procedure utilization, recall retention, and financial collections.
