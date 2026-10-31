# Repository Inventory — Dental Clinic Platform

**Date:** 2026-10-20  
**Phase:** Phase A — Discovery  
**Owner:** Adam (First Element LLC)  
**Target Market:** Dental Clinics in Jordan  
**Reference Document:** `docs/dental/PLAN.md`  

---

## 1. Source Control & Environment

- **Repository Root:** `c:\Users\adama\Downloads\clinic-flow-new-migration-spring-angular\clinic-flow-new-migration-spring-angular`
- **Architecture:** Strangler migration from legacy NestJS/React to target stack:
  - **Backend:** Java 24 / Spring Boot 3.5.6 (Gradle Multi-Project)
  - **Frontend:** Angular 22.2 (RTL Arabic-first, English secondary, Signals, standalone components)
  - **Datastores:** PostgreSQL 16 with `pgvector` (0.7+), Redis 7 (Alpine)
- **Local Runtime Status:**
  - `firstelement-postgres` running on port `5432` (Docker)
  - `firstelement-redis` running on port `6379` (Docker)
  - Spring Boot API running on port `4000` (Management/Actuator on `4101`)
  - Angular Frontend running on port `5173` (Vite dev server)

---

## 2. Modules & Build Commands

### Backend (`settings.gradle.kts`)

Multi-project Gradle build containing 16 subprojects:

| Module | Purpose | Key Artifacts |
| :--- | :--- | :--- |
| `:backend:app-bootstrap` | Main Spring Boot executable (`ApiApplication`) | Config, ports 4000/4101, CORS, Flyway boot trigger |
| `:backend:database` | Flyway schema migrations, DDL ownership | `db/migration/V*.sql`, Flyway baseline fixtures |
| `:backend:shared-kernel` | Cross-cutting domain primitives | `TenantContext`, `CorrelationIdFilter`, `Result`, errors |
| `:backend:tenant-security` | RBAC & Tenancy Enforcement | `TenantQueryGuard`, `UserRole`, `RequirePermission`, session filter |
| `:backend:identity-access` | Authentication & User Management | Bcrypt (cost 12), session cookies, CSRF protection, `/auth/*` |
| `:backend:crm-core` | Core CRM & Clinic Domain | Customers, Conversations, Patients, Odontogram, Schedules, Treatment Plans, Encounters, Invoices, Payments |
| `:backend:inbox-helpdesk` | Omnichannel unified inbox | Conversations timeline, assignments, state machine |
| `:backend:worker-runtime` | Background processing & transactional outbox | `outbox_events`, job attempts, idempotency, lease reaper |
| `:backend:channels` | Channel connectors & webhooks | WhatsApp Cloud API, Telegram, Messenger, MockChannel |
| `:backend:notifications-realtime` | Server-Sent Events (SSE) & alerts | Redis pub/sub bridge, SSE emitters |
| `:backend:workflows-sla-routing` | Automation, SLAs & Deterministic Routing | SLA clocks/calendars, escalation rules, employee assignment |
| `:backend:administration` | Operator control plane & maintenance | Admin read models, maintenance commands, audit log query |
| `:backend:operations-observability` | System health & monitoring | Connector status, queue depth, outbox lag, vector freshness |
| `:backend:reporting-finance` | Finance, usage ledger & rate cards | Effective-dated rate cards, usage ledger, period locks |
| `:backend:reporting-exports` | Data exports & retention | CSV/DOCX streaming, `ExportJob`, signed download URLs |
| `:backend:ai-rag` | Governed AI, RAG & pgvector | Spring AI port, `MockAiClient`, vector search, tool registry, budgets |

#### Build & Test Commands:
- Compile Java: `.\gradlew.bat compileJava compileTestJava`
- Run Backend: `.\gradlew.bat :backend:app-bootstrap:bootRun`
- Run Backend Tests: `.\gradlew.bat test` (Unit tests run locally; DB integration tests require Docker Testcontainers)

---

### Frontend (`frontend/package.json`)

Angular 22 standalone application with Vite/esbuild builder:
- **Language / Core:** TypeScript 6.0, RxJS 7.8, Angular CDK 22.2
- **Styling:** Vanilla CSS design tokens (`var(--pri)`, `var(--surf)`, etc. in `styles.css` / `tokens.css`), logical CSS properties for strict RTL Arabic/LTR English, 0 raw hex colors outside tokens.
- **Commands:**
  - Dev Server: `npm start -- --port 5173`
  - Unit Tests: `npm test` or `ng test --no-watch` (Vitest runner)
  - Typecheck: `npm run typecheck` (`tsc --noEmit` across `app`, `spec`, `e2e`)
  - End-to-End: `npm run e2e` (Playwright)

---

## 3. Database Schema & Migration Tool

- **Migration Tool:** Flyway (`org.flywaydb:flyway-core`, `flyway-database-postgresql`).
- **Current Chain Version:** `v20261023000000` (38 migrations applied).
- **Migration Location:** `backend/database/src/main/resources/db/migration/` and synchronized with `ops/flyway/sql/`.
- **Database Tables (117 relations in total):**
  - **Clinic Specific Tables:**
    - `patients` (UUID id, organizationId, projectId, patientNo, displayName, DOB, phone, email, legalHold)
    - `patient_identities` (national ID, clinic file number, external IDs)
    - `patient_attachments` (metadata, storageKey, sha256, retentionClass, legalHold)
    - `doctors` (id, organizationId, name, specialty, workingDays, workingHours)
    - `procedures` (id, organizationId, projectId, name, specialty, durationMinutes, price, isActive)
    - `clinic_resources` & `clinic_resource_capabilities` (operatories, chairs, devices)
    - `clinic_schedules` & `clinic_time_off` (working windows, leave, exclusion constraints)
    - `appointments` (customerId, patientId, doctorId, procedureId, startTime, endTime, status, cancellation columns)
    - `patient_odontogram_findings` (FDI tooth numbers 11-85, surfaces M/O/D/B/L/I, finding vocabulary)
    - `clinic_finding_vocabulary` & `clinic_finding_vocabulary_template`
    - `treatment_plans` & `treatment_plan_items` (versioned plans, item steps, costs)
    - `clinical_encounters` & `clinical_prescriptions` (encounters, diagnosis, prescriptions)
    - `clinic_invoices` & `clinic_invoice_items` (billing, items, discounts, 3-decimal JOD tax)
    - `clinic_payments` (receipts, payment methods, transaction reference)
    - `clinic_cancellation_policies` (no-show policy, auto-block threshold)

---

## 4. Multi-Tenancy & Authorization Model

- **Tenancy Boundary:**
  - `TenantContext`: Holds `organizationId` (mandatory UUID) and optional `projectId`.
  - Stored in `ThreadLocal` via `TenantFilter` from session cookies or service principals.
  - Hand-written SQL queries strictly enforce tenancy via `TenantQueryGuard` (`WHERE organizationId = ?`).
  - No cross-tenant joins allowed; composite foreign keys enforce `(id, organizationId)` integrity.
- **Role-Based Access Control (RBAC):**
  - Roles (`UserRole` enum): `ADMIN`, `EMPLOYEE`, `CLINIC_STAFF`.
  - `CLINIC_STAFF` holds clinic-specific grants (`doctor:read`, `procedure:read`, `appointment:read`, `analytics:read-project`) without general CRM access.
  - Server-side enforcement: `@RequirePermission(PERMISSIONS.xxx)` on Spring controller methods.
  - Angular route guards (`permissionGuard`) provide UX pre-filtering only.

---

## 5. Audit & Compliance Mechanisms

- **Audit Tables:**
  - `audit_logs` (event, actorId, organizationId, entityType, entityId, payload, timestamp).
  - `admin_audit_events` (administrative actions, role changes, exports).
  - `platform_audit_events` (platform operator actions).
- **Enforcement:**
  - Events emitted through `AuditService` on mutating operations.
  - Read audit currently enforced for export downloads and admin views; patient record read-audit requires standardization.

---

## 6. Storage & File Handling

- **Current Implementation:** `FilesystemExportObjectStore` in `reporting-exports`.
- **Capabilities:**
  - Root directory traversal defense (`normalize()` + prefix validation).
  - Staged atomic writes (`.partial` file moved with `StandardCopyOption.ATOMIC_MOVE`).
  - Read, exists, size, delete operations.
  - Short-lived signed URLs used for exports.
- **Clinic State:** `patient_attachments` table exists in DB, but dedicated secure upload/download service for clinical media/PDFs is not yet exposed via controller.

---

## 7. Existing Finance & Ledger Model

- **Module:** `:backend:reporting-finance`
- **Tables:** `usage_ledger`, `rate_cards`, `rate_card_versions`, `rate_card_version_lines`, `finance_periods`, `finance_adjustments`, `finance_reconciliation_runs`.
- **Key Concepts:**
  - Effective-dated rate cards.
  - Period close/lock/reopen lifecycle (`finance_period_lock_events`).
  - Append-only usage ledger.
  - Clinic Invoices & Payments (`V20261022000000`) store currency as `NUMERIC(15, 3)` (JOD 3 decimal places / fils).

---

## 8. Existing AI Module State

- **Module:** `:backend:ai-rag`
- **Integration:** Spring AI port abstraction.
- **Provider Registry:**
  - Includes deterministic `MockAiClient` (allowing 100% execution without external API keys).
  - Supports Ollama / OpenAI / Anthropic behind common interfaces.
- **Governance & Traceability:**
  - `ai_policies`, `ai_policy_versions`, `ai_budgets`, `ai_approvals`.
  - All executions logged in `ai_runs`, `ai_run_tool_calls`, `ai_run_retrievals`, `ai_run_steps`.
  - Human-in-the-loop: actions requiring approvals produce `ai_approvals` before execution; AI cannot autonomously commit financial or clinical changes.

---

## 9. CI, Testing & Quality Gates

- **Unit Tests:**
  - Frontend: 109 clinic unit tests (100% pass), 14 design-system invariant tests (100% pass), 21 shell tests (100% pass).
  - Backend: Unit tests in `crm-core`, `tenant-security`, `workflows-sla-routing` pass with JUnit 5 and AssertJ.
- **Integration Tests:**
  - Testcontainers with PostgreSQL 16 + pgvector (`FlywayBaselineAcceptanceTest`).
- **Static Analysis:**
  - TypeScript strict mode (`tsc --noEmit`).
  - Design system invariants: 0 emojis, 0 hex color literals outside tokens, logical CSS properties.

---

## 10. Known Risks & Architectural Constraints

1. **Strict JDBC Style:** Hand-written SQL with explicit column names. Must never introduce JPA entities or auto-DDL.
2. **Tenancy Guard:** Every single SQL query touching clinic data must carry `organizationId` parameter binding.
3. **Currency Precision:** Jordan market requires 3 decimal places (`.000` JOD / fils).
4. **Retention Rule (DEC-007):** 10-year retention floor from last procedure for Jordanian clinic tenants.
5. **Clean-Room Requirement:** Zero copy from third-party closed-source PMS tools.
