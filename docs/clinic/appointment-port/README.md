# Appointment Capability — Port Inventory (Phase 5)

**Status:** Read-only inventory. No code, schema, runtime behaviour, configuration or test was changed.

**Produced:** 2026-10-13, branch `migration/spring-angular`, at commit `f4f612e` (working tree has
uncommitted work from other agents; every claim below cites a file and line rather than a commit,
and where a line could be moved by that work it is named by symbol as well).

**Question this document exists to answer:** Phase 5 ("Appointments, waitlist, and check-in")
must not be a from-scratch build. A legacy NestJS controller implements much of it. The expensive
mistakes available are (a) rebuilding what already exists and (b) silently dropping a behaviour
during the port. This file makes both a decision rather than an accident.

**What I did not do, by instruction and in fact:** no Gradle/Maven/npm/yarn/docker/psql/test
command was run; no database was contacted; ports 4000/4200 were untouched; `.env` and no secret
was read. Every schema claim is read from the SQL migration files, not from a live database.
Because no build was run, **every "tests" cell below records what the test source asserts, not an
observed pass.** Where a test is absent I say so; where I could not verify something I say so.

---

## 1. Headline verification: the two counts

The brief said "reported ~21 handlers" for legacy and "7 `@GetMapping` handlers" for Spring.
Both were re-verified. **The Spring count is right; the legacy count is wrong — it is 22, not 21.**

| Claim | Verified value | Evidence |
|---|---|---|
| Legacy `clinic.controller.ts` route decorators | **22** | `apps/api/src/clinic/clinic.controller.ts` lines 74, 83, 92, 107, 119, 125, 134, 143, 156, 166, 172, 179, 190, 201, 210, 222, 232, 245, 251, 257, 267, 285 |
| Legacy route count, independently | **22** | `backend/crm-core/src/test/legacy-notes-project-scope/clinic-permissions.test.ts:80-105` — the `ROUTE_PERMISSIONS` contract table has exactly 22 entries and a test at `:266` asserts the reflected controller metadata equals it |
| Where "21" comes from | stale | `backend/inbox-helpdesk/.../ClinicController.java:40` ("The legacy runtime's clinic module had 21 non-public handlers") and `docs/clinic/requirements-traceability.md:55` and `docs/clinic/open-decisions.md:140` |
| Legacy `public-appointment.controller.ts` | **2** (both `@Public()`, `apps/api/src/clinic/public-appointment.controller.ts:32,37`) | |
| **Total legacy clinic routes** | **24** | |
| Spring `ClinicController` `@GetMapping` | **7**, zero writes | `backend/inbox-helpdesk/src/main/java/com/firstelement/inbox/web/ClinicController.java:97,120,136,150,175,211,238` |

**Follow-up (documentation, not code):** the "21" figure in `ClinicController.java:40`,
`requirements-traceability.md:55` and `open-decisions.md:140` should read 22 non-public + 2 public.
I did not edit those files; they are not mine.

**A second, larger legacy surface exists and is not in either count.** `packages/ai/src/tools/clinic-scheduling.ts`
is a *second, independent* implementation of the entire appointment capability (slots,
availability, create, reschedule, cancel) reached through the AI tool executor, not through the
NestJS controller. It is detailed in §5. If Phase 5 plans only against `clinic.controller.ts`, it
will miss it.

---

## 2. Legacy inventory — `apps/api/src/clinic/clinic.controller.ts`

Common to all 22:

- **Tenant scope.** No route carries a `:projectId` path parameter, so `PermissionsGuard` supplies
  *no* project scope for any of them (`clinic.controller.ts:25-33`). Enforcement lives in
  `LocalClinicAdapter` / `ClinicService`, which take `TenantContext` first and key every query on
  `accessibleProjectsScope(ctx)` or `projectScope(ctx, …)`. A caller-supplied `projectId` is a
  request, never an authorization (`clinic.service.ts:29-40`, `local-clinic.adapter.ts:138-152`).
- **Refusal shape.** Every denial is `404 {code: NOT_FOUND, message: 'Resource not found'}` —
  deliberately identical to "record does not exist", because this surface carries patient names and
  phone numbers (`clinic.controller.ts:45-53`; `local-clinic.adapter.ts:392-397`).
- **Authorization.** Every handler declares `@RequirePermissions(...)`; permission names are listed
  per row.
- **Tables.** Reads/writes are recorded per row below. The four tables involved are `doctors`,
  `procedures`, `appointments`, `audit_logs`.

### 2.1 Roster and catalogue (10 handlers)

| # | Method + path | One sentence | Shape | Tables R / W | Auth | Verdict |
|---|---|---|---|---|---|---|
| 1 | `GET /clinic/doctors` (`:74`) | The bookable roster for the caller's project. | Q `projectId?`; R array of `{id,name,specialty,department,workingDays,startHour,endHour,slotDuration,isActive}` (`local-clinic.adapter.ts:318-328`) | `doctors` R | `DOCTOR_READ` | **PORT** |
| 2 | `GET /clinic/doctors/manage` (`:83`) | The roster plus clinician email/phone and appointment count. | Q `projectId?`; R `+ email, phone, appointmentCount` (`local-clinic.adapter.ts:360-373`) | `doctors` R (`_count.appointments`) | `DOCTOR_READ_MANAGEMENT` | **DROP from Phase 5** — Phase 33 deliberately split this from the roster (`Permission.java:121-130`). Re-adding it collapses a decision that is already made and documented. Out of scope; revisit as its own slice. |
| 3 | `POST /clinic/doctors` (`:92`) | Creates a clinician in the caller's own first project. | `@Body() body: any` → adapter `{name, specialty, department?, email?, phone?, workingDays?, startHour?, endHour?, slotDuration?}` | `doctors` W, `audit_logs` W (`DOCTOR_CREATED`) | `DOCTOR_CREATE` | **NEEDS-REBUILD** — Spring has no doctor write path and no `doctor:create` permission (`Permission.java:132-138` names its absence deliberately). Also the documented source of the 2026-10-08 breach: `{...body}` spread **before** `projectId` (`clinic.controller.ts:101-104`). Port the fixed shape only, with a DTO. |
| 4 | `PATCH /clinic/doctors/:id` (`:107`) | Updates a clinician in place; tenant/project not body-assignable. | `@Body() body: any` → enumerated fields only (`local-clinic.adapter.ts:463-474`) | `doctors` R+W, `audit_logs` W (`DOCTOR_UPDATED`, before/after diff) | `DOCTOR_UPDATE` | **NEEDS-REBUILD** — same reason as #3 |
| 5 | `DELETE /clinic/doctors/:id` (`:119`) | Deletes a clinician; audits a full pre-image. | none | `doctors` R+W, `audit_logs` W (`DOCTOR_DELETED`) | `DOCTOR_DELETE` | **NEEDS-REBUILD** — same; note FK `ON DELETE SET NULL` on `appointments.doctorId` (baseline `:2622`) so deletes orphan rather than block |
| 6 | `GET /clinic/procedures` (`:125`) | The active procedure catalogue. | Q `projectId?`; R `{id,name,specialty,durationMinutes,bufferMinutes,price,isActive}` (`local-clinic.adapter.ts:661-669`) | `procedures` R | `PROCEDURE_READ` | **PORT** |
| 7 | `GET /clinic/procedures/manage` (`:134`) | Catalogue plus appointment count. | Q `projectId?`; R `+ appointmentCount` | `procedures` R | `PROCEDURE_READ_MANAGEMENT` | **DROP from Phase 5** — same split as #2 |
| 8 | `POST /clinic/procedures` (`:143`) | Creates a procedure in the caller's own first project. | `@Body() body: any` → `{name, specialty, durationMinutes, bufferMinutes?, price?}` | `procedures` W, `audit_logs` W | `PROCEDURE_CREATE` | **NEEDS-REBUILD** |
| 9 | `PATCH /clinic/procedures/:id` (`:156`) | Updates a procedure in place. | `@Body() body: any` | `procedures` R+W, `audit_logs` W | `PROCEDURE_UPDATE` | **NEEDS-REBUILD** |
| 10 | `DELETE /clinic/procedures/:id` (`:166`) | Deletes a procedure; audits a pre-image. | none | `procedures` R+W, `audit_logs` W | `PROCEDURE_DELETE` | **NEEDS-REBUILD** |

### 2.2 Duration, slots, availability (3 handlers) — the scheduling engine

| # | Method + path | One sentence | Shape | Tables R / W | Auth | Verdict |
|---|---|---|---|---|---|---|
| 11 | `GET /clinic/procedures/:id/duration` (`:172`) | Returns one procedure's duration, defaulting to 30 when the row is missing or has 0. | R `{procedureId, durationMinutes}` (`clinic.controller.ts:176`) | `procedures` R | `PROCEDURE_READ` | **NEEDS-REBUILD / DROP.** The Spring catalogue already projects `durationMinutes` (`ClinicModels.java:120`), so this route is redundant. **But the legacy default is a defect**: `clinic.service.ts:52-55` returns `30` for a procedure the caller cannot see, which converts "not yours / not there" into "thirty minutes". `clinic-permissions.test.ts:200` records exactly this. If kept, it must 404; my recommendation is DROP and read `durationMinutes` from the existing `GET /clinic/procedures/{id}`. |
| 12 | `GET /clinic/slots` (`:179`) | Computes collision-free slots for one doctor/procedure/date. | Q `doctorId, procedureId, date(YYYY-MM-DD)`; R `AvailableSlotsResult` = `{doctorId, doctorName, procedureId, procedureName, durationMinutes, date, isWorkingDay, slots[], reason?}` (`scheduling-engine.service.ts:5-15,146-156`) | `doctors` R, `procedures` R, `appointments` R | `SCHEDULE_AVAILABILITY_READ` | **PORT — highest-value single route in this document.** This is the deterministic availability algorithm that §3 of the traceability doc marks MISSING (A-4). See caveats at §4.1. |
| 13 | `GET /clinic/check-availability` (`:190`) | Validates one exact instant and returns alternatives when refused. | Q `doctorId, procedureId, dateTime`; R `{available, code, reason, alternativeSlots[]}` or `{available:true, code:'VALID', doctor, procedure, requestedTime}` (`clinic.service.ts:66-94`) | as #12 | `SCHEDULE_AVAILABILITY_READ` | **PORT** — same caveat; this is the validation path `POST /appointments` also uses |

**Engine behaviour to carry across verbatim (`scheduling-engine.service.ts`):**

- Working-day check against `doctors.workingDays` (`0 = Sunday`, `:81-94`).
- Working-hour window from `startHour`/`endHour` strings parsed to minutes (`:96-104`, `:195-202`).
- Busy-interval construction from existing appointments, `[start, start+durationMinutes]` (`:113-121`).
- Overlap test `slotStart < busy.end && slotEnd > busy.start` (`:135-137`, `:229`).
- Slot step `min(30, doctor.slotDuration)` (`:124`) — **note this differs from the AI copy, which
  hardcodes 30** (`packages/ai/src/tools/clinic-scheduling.ts:238`). Two implementations, two answers.
- Refusal codes: `OUTSIDE_WORKING_HOURS`, `DOCTOR_UNAVAILABLE_ON_DATE`, `DURATION_CONFLICT`,
  `SLOT_OCCUPIED`, `VALID` (`:17-22`).
- Alternative slots: up to 5 days forward, 4 slots/day, stop after 2 days with slots, first 3
  returned (`:249-275`).
- Alternative doctors: same specialty (case-insensitive), excluding the requested one, each
  re-validated (`:280-311`).

### 2.3 Appointment lifecycle — the write path (3 handlers)

| # | Method + path | One sentence | Shape | Tables R / W | Auth | Verdict |
|---|---|---|---|---|---|---|
| 14 | `POST /clinic/appointments` (`:201`) | Validates then books an appointment, then writes an audit row. | `@Body() BookingRequestInput` = `{doctorId*, procedureId*, appointmentDate*, patientName?, patientPhone?, patientEmail?, customerId?, notes?, isAiProposed?, aiProposalReason?, ticketNumber?}` (`clinic.service.ts:7-19`). R = 15-field `ClinicAppointment` (`clinic-adapter.interface.ts:25-48`) | R: `doctors`, `procedures`, `appointments`; W: `appointments` (`:757-780`), `audit_logs` (`APPOINTMENT_CREATED`, `clinic.service.ts:149-169`) | `APPOINTMENT_CREATE` | **PORT.** This is acceptance scenario 1. |
| 15 | `PATCH /clinic/appointments/:id/reschedule` (`:210`) | Revalidates the new instant against the appointment's own doctor/procedure, moves it, resets status to `PENDING`, audits. | `@Body('newDate')` or `@Body('newDateTime')` (either name accepted, `:218`) | R: `appointments`, `doctors`, `procedures`; W: `appointments` (`local-clinic.adapter.ts:808-814`), `audit_logs` (`APPOINTMENT_RESCHEDULED`) | `APPOINTMENT_RESCHEDULE` | **PORT** |
| 16 | `POST /clinic/appointments/:id/cancel` (`:222`) | Sets status `CANCELLED` and appends the reason to `notes`; frees the slot by exclusion from future slot computation. | `@Body('reason')?` | R+W: `appointments` (`local-clinic.adapter.ts:848-854`), `audit_logs` (`APPOINTMENT_CANCELLED`) | `APPOINTMENT_CANCEL` | **PORT** — this is acceptance scenario 9 |

**Behaviours inside #14–#16 that a naive port drops:**

- `createAppointment` is **not transactional**. It validates, writes the appointment, then writes the
  audit row in a second call (`clinic.service.ts:128,149`). A crash between them leaves an
  unaudited booking. The audit is what Phase 26 needed and could not get. **The port should fix
  this, not reproduce it.**
- The audit `actorType` is `'AI'` for **reschedule and cancel** even when a human staff member did
  it (`clinic.service.ts:215,245`) — only create discriminates (`:154`). This is wrong and would make
  an audit row assert something untrue (ADR 0010 §2). **Do not port the bug.**
- Cancel/reschedule scope-check the row first and 404 before any write
  (`clinic.service.ts:183-189`, `:230-236`).
- Slot freeing on cancel is **implicit**: `getExistingAppointments` filters
  `status notIn ['CANCELLED','NO_SHOW']` (`local-clinic.adapter.ts:725-727`). There is no separate
  occupancy table.
- `rescheduleAppointment` only re-validates when `doctorId && procedureId` are both present
  (`clinic.service.ts:191`) — an appointment missing either is moved with **no** conflict check.
- `durationMinutes` is stored per appointment, copied from the procedure at booking time
  (`clinic.service.ts:121`). It is a snapshot, not a live reference; a later procedure-duration
  change does not move existing appointments.
- `isAiProposed` defaults to `false` on the HTTP path (`clinic.service.ts:142`) but the **column
  default is `true`** (`schema.prisma:1477`). Mixed defaults — see §6.

### 2.4 AI simulator (1 handler)

| # | Method + path | One sentence | Shape | Tables R / W | Auth | Verdict |
|---|---|---|---|---|---|---|
| 17 | `POST /clinic/ai-simulator/chat` (`:232`) | A rule-based (not model-based) NL front desk: parses doctor/procedure/date/time/intent by substring matching, then **really books** through `ClinicService.createAppointment`. | `@Body() body: any`; R `SimulatorChatResponse` = `{reply, intent, entities, toolExecutions[], suggestedSlots?, createdAppointment?, ticketNumber?, locale}` (`ai-simulator.service.ts:35-52`) | R: `doctors`, `procedures`, `appointments`, `customers`; W: `appointments` (`:371-382`) | **`CLINIC_AI_SIMULATE` + `APPOINTMENT_CREATE`** — both, deliberately, because the simulator writes appointments (`clinic.controller.ts:233-237`; asserted at `clinic-permissions.test.ts:306`) | **NEEDS-REBUILD — and drop the booking half.** The parser is hard-coded keyword matching, including hard-coded Arabic names (`ai-simulator.service.ts:107-115`) and a fabricated `aiSavedHours`/funnel (`:443-447`). What is worth porting is *nothing here*: the real AI path is `packages/ai` (§5). **Recommendation: DROP the simulator entirely, and do not port its booking behaviour into any route.** Recorded rather than silently dropped because the legacy frontend has a UI for it (`apps/web/src/routes/AiReceptionistPage.tsx`). |

### 2.5 Reminders (4 handlers) + patient public routes (2)

| # | Method + path | One sentence | Shape | Tables R / W | Auth | Verdict |
|---|---|---|---|---|---|---|
| 18 | `GET /clinic/reminders` (`:245`) | Summary of appointments in the next 48h + reminder stats. | R `{stats:{upcomingNext48h, remindersSent, confirmedCount, confirmationRatePercent, noShowRiskCount}, recentReminders[]}` (`reminders.service.ts:23-29,112-123`) | `appointments` R | `REMINDER_READ` | **NEEDS-REBUILD, but defer to Phase 6.** Note two defects to *not* port: the reminder store is an **in-memory `Map`** seeded with three demo records and three demo patients (`:46,56-76`), and `confirmationRatePercent` floors at 85 when the store is empty (`:110`). |
| 19 | `POST /clinic/reminders/send-batch` (`:251`) | "Send" reminders for appointments in the next 24h with status `PENDING`/`SCHEDULED`, up to 20. | R `{sent24hCount, sent2hCount, records[]}` | `appointments` R | `REMINDER_SEND` | **NEEDS-REBUILD / defer.** **No message is actually sent.** It mutates an in-memory map and marks every record `'DELIVERED'` without contacting any channel (`:214-244`). Porting this would create a route that reports delivery it did not achieve — the one failure mode N-5 in the traceability doc calls out. |
| 20 | `POST /clinic/reminders/send/:appointmentId` (`:257`) | Creates one reminder record for one appointment and audits `REMINDER_SENT`. | `@Body('channel')?: 'WHATSAPP'\|'SMS'`; R `ReminderRecord` (`reminders.service.ts:7-21`) | R: `appointments`; W: `audit_logs` (`:169-185`), in-memory map | `REMINDER_SEND` | **NEEDS-REBUILD / defer.** Same "SENT without sending" defect: status is set `'SENT'` at `:161` and no connector is called. Also mints `actionToken` as `tok-${id.slice(0,8)}-${Date.now()}` (`:149`) — predictable-ish and non-persistent, which is why the public token route only ever resolves seeded demo tokens. |
| 21 | `POST /clinic/reminders/respond` (`:267`) | Records a patient CONFIRM / RESCHEDULE / CANCEL reply: writes `status` and **appends a `[WhatsApp Action: …]` line to `notes`**. | `@Body('appointmentId'), ('action'), ('reason')?`; R `{success, action, status, message, appointment}` (`reminders.service.ts:32-38`) | R+W: `appointments` (`:285-293`) | `REMINDER_RECORD_RESPONSE` | **NEEDS-REBUILD / defer.** `_reason` is accepted and discarded (`:253`). Appending an audit line into the clinical `notes` column destroys the Spring read model's `notes` non-projection guarantee (`ClinicModels.java:24-27`) — if this is ported, it needs its own table. |
| 22 | `GET /clinic/analytics` (`:285`) | Executive KPI dashboard. | Q `days?`; R `kpis`, `conversionFunnel`, `topProcedures`, `clinicianUtilization`, `channelAcquisition`, `dailyTrends`, `slaComparison` (`clinic.service.ts:419-448`) | `appointments`, `procedures`, `doctors`, `conversations`, `customers` | `ANALYTICS_READ_PROJECT` | **PORT the permission, DROP most of the payload.** Already argued in Spring at `ClinicReadService.java:241-263`: `aiBookingConversionRate = Math.max(75, raw)` (`:278`), `confirmationRate = Math.max(85, raw)` (`:281`), `noShowMitigationImprovement: 78` literal, `estimatedRevenueJod: Math.max(4850, …)` (`:428`), `aiSavedHours: Math.max(42.5, …)` (`:429`), `averageAiResponseTimeSec: 1.2` literal, `slaComparison` all literals (`:443-447`), and `dailyTrends` is a **fabricated series** computed from `i % 3` (`:408-414`). `channelAcquisition` floors at 18/12/8 (`:376-380`). The Spring `GET /clinic/analytics` already replaces this honestly. **DROP the legacy payload; keep the route and permission.** |

### 2.6 The public, unauthenticated controller (`public-appointment.controller.ts`)

| Method + path | One sentence | Auth | Verdict |
|---|---|---|---|
| `GET /public/appointments/:token` (`:32`) | Reads an appointment for a patient holding the unguessable `actionToken`; returns patient name, phone, doctor, time. | none — **the link is the capability** (`:10-12`) | **NEEDS-REBUILD / defer to Phase 6.** Security note: this route discloses patient name + phone on token possession alone (`reminders.service.ts:449-461`). Only token resolution is permitted — a bare appointment id must never resolve (`:344-355`, `:411-418`). |
| `POST /public/appointments/:token/respond` (`:37`) | Applies CONFIRM / RESCHEDULE / CANCEL by token. | none | **NEEDS-REBUILD / defer.** Writes `appointments.status` and appends to `notes` (`reminders.service.ts:380-388`). |

### 2.7 Legacy test coverage per handler

Two suites cover the legacy surface; both live **under the Spring tree** and are excluded from the
default Vitest run (`backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts:11-14`).

| Suite | What it asserts | Where |
|---|---|---|
| `clinic-permissions.test.ts` | All 22 routes declare the named permission; ADMIN/EMPLOYEE/no-permission matrices; refusals are byte-identical 404s; a body cannot move a row between tenants | `:80-105`, `:266,275,297,306`, `:390,403,420`, `:454`, `:483,542,560`, `:572,615,623,637`, `:644,693` |
| `clinic-project-scope.test.ts` | Same-project reach; same-tenant cross-project refusal; cross-tenant refusal; reminders token path; appointments module scope | `:311,339,346,347,376,389,404,420,437,524,542,543,555,578,590,603,623,631,697,754,795` |
| `clinic-roster-audit.test.ts` | Roster audit rows: one per mutation, diffs, no-ops, contact details as presence not value, refused writes write nothing | `:259,290,310,319,328,362,386,410,429,457,474,493,525,536,550,571` |
| `clinic-role-grant.test.ts` | `CLINIC_STAFF` holds exactly the front-desk grant | `:66,82,88,101,118,123,148,161,170,178` |

**Per-handler verdict on test coverage:**

| Handlers | Covered? |
|---|---|
| #1, #6, #10, #11, #12, #13, #18, #19, #20, #21, #22 | Yes — permission + refusal, and `AUTHORISED_CALLS` at `clinic-permissions.test.ts:114+` drives one success per route |
| #2, #7, #9, #14, #15, #16 | Yes for permission/refusal/scope. **No behavioural test** of booking, rescheduling or cancellation semantics anywhere in the repository |
| #3, #4, #5, #8 | Yes — permission, refusal, and the roster audit suite |
| #17 | Yes for permission only (`clinic-permissions.test.ts:306`, `:185`). **No test** of the simulator's parsing or booking behaviour |
| Public routes | Yes, thoroughly (`clinic-permissions.test.ts:318-388`, `clinic-project-scope.test.ts:578-622`) |

I searched the whole repository for a test that asserts a booking actually produces a row, that a
cancel frees a slot, or that rescheduling re-validates. **I found none.** The strongest evidence of
a successful legacy booking is `clinic-permissions.test.ts:114+` asserting the response status.
That is a gap in the legacy suite as well as in Spring.

---

## 3. Spring inventory — `backend/inbox-helpdesk/.../ClinicController.java`

Common to all 7:

- **Tenant scope.** Every `projectId` arrives as a `@RequestParam`, is parsed by
  `RequestParams.optionalUuid`, and is handed to `TenantQueryGuard.scope(caller, requestedProjectId)`
  (`ClinicController.java:48-61`). An absent `projectId` means "every project I may reach", and a
  principal with no memberships receives `1 = 0` (`ClinicReadService.java:118-119`). `ClinicScope`
  has no unchecked constructor (`ClinicRepository.java:20-22`).
- **Authorization.** `@RequirePermission` on the handler **and** an explicit
  `AuthorizationService.check` in the service — both load-bearing (`ClinicReadService.java:21-34`).
- **Refusal shape.** A missing grant is **403**; a scope denial is a **non-disclosing 404**; the
  two are deliberately kept apart (`ClinicReadService.java:281-290`). Every `/{id}` miss returns the
  same byte-identical 404 (`ClinicController.java:259-281`).
- **Writes.** None. `ClinicReadService` is `@Transactional(readOnly = true)` on every method
  (`:122,139,157,168,189,216,265`) and `ClinicContractHttpIntegrationTest.java:256-257` asserts no
  service method is named like a write.

| # | Method + path | One sentence | Response shape | Tables R / W | Auth | Tests |
|---|---|---|---|---|---|---|
| S1 | `GET /api/v1/clinic/doctors` (`:97`) | The clinician roster in scope. | `ClinicCataloguePage<Doctor>` — `{count, items[]}`; Doctor = `{id, organizationId, projectId, name, specialty, department, workingDays, startHour, endHour, slotDuration, isActive, createdAt, updatedAt}` (`ClinicModels.java:65-79`). **No email/phone.** | `doctors` R (`ClinicRepository.java:66-72`) | `DOCTOR_READ` | `ClinicReadModelHttpIntegrationTest.java:98` (contact details absent), `:126`; `ClinicContractHttpIntegrationTest.java:114`; `ClinicOpenApiContractTest.java:74` |
| S2 | `GET /api/v1/clinic/doctors/{doctorId}` (`:120`) | One clinician; 404 for foreign, nonexistent and left-project alike. | `Doctor` or 404 | `doctors` R (`:311-316`) | `DOCTOR_READ` | `ClinicReadModelHttpIntegrationTest.java:552,683`; `ClinicContractHttpIntegrationTest.java:158,160,162` |
| S3 | `GET /api/v1/clinic/procedures` (`:136`) | The procedure catalogue; nullable `price` stays distinct from 0. | `ClinicCataloguePage<Procedure>` (`ClinicModels.java:114-126`) | `procedures` R (`ClinicRepository.java:74-80`) | `PROCEDURE_READ` | `ClinicReadModelHttpIntegrationTest.java:175` (null ≠ 0), `:651` |
| S4 | `GET /api/v1/clinic/procedures/{procedureId}` (`:150`) | One procedure; 404 non-disclosing. | `Procedure` or 404 | `procedures` R | `PROCEDURE_READ` | `ClinicContractHttpIntegrationTest.java:289-290` |
| S5 | `GET /api/v1/clinic/appointments` (`:175`) | One keyset page of the schedule. | `ClinicAppointmentPage` = `{items[], count, nextCursor, from, to}`; Appointment = `{id, organizationId, projectId, customerId, doctorId, procedureId, title, patientName, appointmentDate, durationMinutes, status, service, doctorName, isAiProposed, ticketNumber, createdAt, updatedAt}`. **No `notes`, no `aiProposalReason`, no `patientPhone`, no `patientEmail`.** (`ClinicModels.java:142-161`; `ClinicRepository.java:115-128`) | `appointments` R | `APPOINTMENT_READ` | `ClinicReadModelHttpIntegrationTest.java:142` (projection), `:196` (keyset, no dupes/skip), `:225` (unknown status → 400) |
| S6 | `GET /api/v1/clinic/appointments/{appointmentId}` (`:211`) | One appointment; 404 non-disclosing. | `Appointment` or 404 | `appointments` R (`ClinicRepository.java:148-156`) | `APPOINTMENT_READ` | `ClinicReadModelHttpIntegrationTest.java:686`; `ClinicContractHttpIntegrationTest.java:291-292` |
| S7 | `GET /api/v1/clinic/analytics` (`:238`) | Three metric groups, each carrying its own source rows, window, derivation and drill-through path. | `ClinicAnalytics` | `appointments` R, `procedures` R, `doctors` R (`ClinicRepository.java:189,229,264`) | `ANALYTICS_READ_PROJECT` | `ClinicReadModelHttpIntegrationTest.java:254,286,309,337,377,411,442,482,507,520` |

**Spring constraints that bound any port** (all deliberate, all documented in place):

- `Permission.java:121-138` names, in terms, every clinic permission that is **absent**:
  `doctor:read-management`, `procedure:read-management`, `doctor:create/update/delete`,
  `procedure:create/update/delete`, `appointment:create/reschedule/cancel`, `reminder:*`,
  `schedule:read-availability`, `clinic:simulate-ai`. The reason given: a mutation permission with
  no route is a grant nobody can exercise and an audit vocabulary naming actions the runtime cannot
  perform.
- `RolePermissions.java:116-118` (EMPLOYEE) and `:138-143` (CLINIC_STAFF) grant exactly the three
  read permissions.
- `ClinicController.java:38-46` states the bounded-slice rationale explicitly and names the legacy
  `POST /clinic/doctors` spread as the reason the write path was excluded.
- `ClinicOpenApiContractTest.java:138-147` asserts no `/api/v1/clinic*` route appears on the
  outstanding-work table.

**Two stale references found in the Spring source** (documentation defects, not bugs; not fixed):
`ClinicController.java:301` names `ClinicTenantIsolationHttpIntegrationTest`, which **does not
exist** — the class is `ClinicReadModelHttpIntegrationTest`. `ClinicReadService.java:32` names
`ClinicRoutePermissionTest`, which **does not exist** — the test is
`ClinicContractHttpIntegrationTest.everyHandlerDeclaresItsPermission` (`:186`).

---

## 4. The gap list — behaviours present in legacy, absent from Spring

This is the core deliverable. "Absent from Spring" means absent from production code; test fixtures
that *insert* appointment rows are not a capability.

### 4.1 Write path — entirely absent

| Behaviour | Legacy evidence | Spring status | Smallest missing piece |
|---|---|---|---|
| Book an appointment | `clinic.controller.ts:201`; `clinic.service.ts:96-172`; `local-clinic.adapter.ts:752-800` | **Absent.** The only `insert into appointments` in the Spring tree is a test fixture (`ClinicFixture.java:188`) | Route + `POST` service + repository insert + `APPOINTMENT_CREATE` in `Permission` + grant in `RolePermissions` |
| Reschedule | `clinic.controller.ts:210`; `clinic.service.ts:174-227` | Absent | as above + `APPOINTMENT_RESCHEDULE` |
| Cancel | `clinic.controller.ts:222`; `clinic.service.ts:229-254` | Absent | as above + `APPOINTMENT_CANCEL` |
| Edit appointment details (title/notes) | no route; only status/date/notes mutated internally | Absent, and **absent from legacy as a route too** | New work — do not invent it in Phase 5 |
| Audit row per mutation | `clinic.service.ts:149,210,240`; `local-clinic.adapter.ts:180-201` | Absent — no appointment write means no appointment audit | Reuse the existing audit writer, **in the same transaction** as the write |
| Availability/validation engine | `scheduling-engine.service.ts:54,162,249,280` | **Absent.** No availability class, route or service exists anywhere in `backend/` | Port the algorithm; expose via `schedule:read-availability` |
| `GET /clinic/slots` | `clinic.controller.ts:179` | Absent | Route + permission |
| `GET /clinic/check-availability` | `clinic.controller.ts:190` | Absent | Route + permission |
| Status lifecycle writes (`PENDING`/`APPROVED`/`COMPLETED`/`CANCELLED`/`NO_SHOW`) | enum-like strings in `local-clinic.adapter.ts:770,812,851`; whitelist in `local-clinic.adapter.ts:726` | **Read-only.** `ClinicModels.AppointmentStatus` (`:175-206`) is used **only as a filter whitelist** (`ClinicReadService.java:202-203`) and validated at the filter boundary, not on write | Lifecycle write service; **the column is unconstrained text** (`baseline:624`), so a CHECK or enum is a schema decision |
| `status` default | `baseline:624` `DEFAULT 'PENDING'`; `schema.prisma:1474` | Read-only | Set explicitly on write rather than relying on the default |
| Booking source (`isAiProposed`) | `clinic.service.ts:142,379`; `packages/ai/.../clinic-scheduling.ts:589` | **Projected** (`ClinicModels.java:156`) but never set | One boolean today; the plan wants a taxonomy (`A-6`). **A schema change.** |
| Patient link on an appointment (`customerId`) | `clinic.service.ts:130` | **Projected** (`ClinicModels.java:145`) but Spring's clinic projection **drops it** — traceability P-1: "the clinic projection drops `customerId`, so there is no path from a clinic row to a patient file" | Depends on DEC-015 (Patient ≠ Customer) |

### 4.2 Non-write capabilities absent from Spring

| Behaviour | Legacy | Spring | Note |
|---|---|---|---|
| `GET /clinic/doctors/manage` (contact details) | `:83` | Absent **by decision** | Drop from Phase 5 |
| `GET /clinic/procedures/manage` | `:134` | Absent **by decision** | Drop from Phase 5 |
| Doctor/procedure create-update-delete | `:92,107,119,143,156,166` | Absent **by decision** | Out of Phase 5 scope; a later slice |
| `GET /clinic/reminders` | `:245` | Absent | Phase 6. Legacy returns **demo data** from an in-memory map — do not port the payload |
| `POST /clinic/reminders/send-batch` | `:251` | Absent | Phase 6. Legacy sends nothing |
| `POST /clinic/reminders/send/:id` | `:257` | Absent | Phase 6. Legacy sends nothing |
| `POST /clinic/reminders/respond` | `:267` | Absent | Phase 6. Needs its own table, not `notes` |
| `GET/POST /public/appointments/:token*` | `public-appointment.controller.ts:32,37` | Absent | Phase 6. Token capability model is sound and worth preserving |
| AI booking tools (7 of them) | `packages/ai/src/tools/definitions.ts:122,140,158,174,195,213,236,257` | **Only `appointment.propose`, which throws unconditionally** (`AppointmentProposalTool.java:101-106`) | See §5 |
| Reminder queue / `QueueName` member | none in legacy either | `QueueName.java:26-48` — **no reminder queue exists in either runtime** | New work |
| Waitlist | **none in legacy** | none | New work; `tasks` table is unclaimed (traceability A-10) |
| Check-in / waiting room | none | none | New work |
| Status history | none | none | New work |

### 4.3 Behaviours Spring has that legacy does not (do not lose these in the port)

| Spring behaviour | Where |
|---|---|
| Keyset pagination with a mandatory `id` tie-break | `ClinicRepository.java:85-128`; proven at `ClinicReadModelHttpIntegrationTest.java:196` |
| Unknown `status` → 400 naming the accepted set (never silently dropped) | `ClinicModels.java:191-205`; `ClinicReadModelHttpIntegrationTest.java:225` |
| Window validation: `from < to`, max 366 days, echoed on every response | `ClinicController.java:311-353` |
| Every metric carries `count`, bounded source ids, `sourceRowCount`, derivation, `drillThroughPath` | `ClinicRepository.java:189-284`; proven at `ClinicReadModelHttpIntegrationTest.java:442,482,507,520` |
| Clinician email/phone absent **from the SQL**, not masked in the response | `ClinicRepository.java:24-31` |
| `notes` / `aiProposalReason` / `patientPhone` / `patientEmail` absent from the appointment projection | `ClinicModels.java:20-41`; proven at `ClinicReadModelHttpIntegrationTest.java:142` |
| Byte-identical 404 for foreign / nonexistent / left-project | `ClinicReadModelHttpIntegrationTest.java:552` |
| 403 for a missing grant, distinct from 404 for a scope denial | `ClinicReadModelHttpIntegrationTest.java:745` |
| Declared refusals are all reachable; no 5xx declared; no route on the outstanding-work table | `ClinicOpenApiContractTest.java:124,138,190` |

---

## 5. The second legacy appointment implementation — `packages/ai`

This is the most expensive thing to discover late, so it is recorded here in full.

`packages/ai/src/tools/clinic-scheduling.ts` (762 lines) implements a **complete, second** appointment
capability, reached not through the NestJS controller but through the AI tool executor:

```
apps/api/src/channels/webhooks.controller.ts:55   (inbound provider webhook)
  → apps/api/src/messaging/message-queue.service.ts:103   orchestrator.run(...)
    → packages/ai/src/orchestrator/orchestrator.ts:78      new ToolExecutor(prisma)
      → packages/ai/src/tools/executor.ts:256,280,295,325,348
        → packages/ai/src/tools/clinic-scheduling.ts:264,166,512,625,707
```

Functions, with what each does and its tenant scoping:

| Function | Line | Behaviour | Notes |
|---|---|---|---|
| `getClinicDoctors` | 86 | Roster by optional specialty | `organizationId` + `projectId` (`:95`) |
| `getClinicProcedures` | 115 | Catalogue by optional specialty | `:121` |
| `getProcedureDuration` | 141 | Duration **+ buffer** (`totalOccupiedMinutes`) | `:161` — the *only* place in the repo that adds `bufferMinutes` into an occupancy calculation. The Spring read model projects `bufferMinutes` (`ClinicModels.java:121`) but **nothing reads it** (traceability D-3: "dead data"). This is the reader that is missing. |
| `findDoctorAvailableSlots` | 166 | Slot list | **step is hardcoded 30** (`:238`), not `doctor.slotDuration` as in the Nest engine (`scheduling-engine.service.ts:124`) |
| `checkDoctorAvailability` | 264 | Working day → hours → collision, with alternative slots **and** alternative doctors | `SLOT_OCCUPIED` returns `alternativeDoctors` (`:396`) — richer than the HTTP route's |
| `createAppointmentValidated` | 512 | Books, copies customer displayName/phone/email onto the row, audits `create_appointment` with `actorType: 'AI_AGENT'` | Sets `status: 'APPROVED'` (`:588`) and `isAiProposed: false` (`:589`) — **directly contradicting the HTTP path's `PENDING`/`false`** |
| `rescheduleAppointmentValidated` | 625 | Revalidates, moves, sets `APPROVED` | `:675` |
| `cancelAppointmentValidated` | 707 | Sets `CANCELLED`, notes `Cancelled: <reason>` | `:733` |

Tool definitions: `get_doctors`, `get_procedures`, `get_procedure_duration`, `check_availability`,
`find_available_slots`, `create_appointment`, `reschedule_appointment`, `cancel_appointment`
(`definitions.ts:122,140,158,174,195,213,236,257`).

**Authorization on this path:** `ToolExecutor.execute` denies a tool not in `ctx.enabledTools`
(`executor.ts:71-73`). Tenant and project always come from the tool context, never from tool
arguments (`executor.ts:49-51`). The helpers re-check each model-supplied id against scope and
return the same "not found" as a genuinely missing row (`clinic-scheduling.ts:14-19`).

**Test coverage — the best-tested appointment code in the repository**
(`packages/ai/src/tools/clinic-scheduling.test.ts`): `:297,309,318,333,349,364,392,416,453,488`
(happy paths, non-working day, outside hours, collision + alternatives, create/reschedule/cancel
with audit, executor path) and `:537-725` (nine cross-tenant negatives plus
`every clinic lookup carries the organization, not just the project` at `:710`).

**Verdict: PORT the logic, NOT the surface.**

- The availability algorithm and the three mutation helpers are the most complete, best-tested
  version in the repo. Use them as the specification for the Spring availability service.
- **Do not port the AI execution path as-is.** ADR 0007 Part 2 requires human approval for
  consequential actions, and the Spring `AppointmentProposalTool` encodes that
  (`AppointmentProposalTool.java:16-23`, `perform` throws at `:101`). The legacy path books directly
  from a model turn with no approval. Porting that would regress a deliberate control.
- **Reconcile the two implementations before porting either.** They disagree on slot step (30 vs
  `doctor.slotDuration`), on buffer (`duration + buffer` vs `duration`), on resulting status
  (`APPROVED` vs `PENDING`), and on `isAiProposed` (`false` vs caller-supplied/`true` default).
  Picking one without deciding is how Phase 5 ships two answers to "is this slot free?".

---

## 6. Schema facts, read from the SQL migrations

### 6.1 The `appointments` table as it exists

`V20260814000000__baseline_verified_live_schema.sql:611-635`:

```
"id" uuid NOT NULL, "organizationId" uuid NOT NULL, "projectId" uuid NOT NULL,
"customerId" uuid, "doctorId" uuid, "procedureId" uuid,
"title" text NOT NULL, "patientName" text, "patientPhone" text, "patientEmail" text,
"appointmentDate" timestamp(3) without time zone NOT NULL,
"durationMinutes" integer DEFAULT 30 NOT NULL,
"status" text DEFAULT 'PENDING'::text NOT NULL,          -- unconstrained text
"service" text, "doctorName" text,
"isAiProposed" boolean DEFAULT true NOT NULL,
"aiProposalReason" text, "notes" text, "ticketNumber" text, "ticketDate" timestamp(3),
"createdById" uuid, "createdAt" timestamp(3), "updatedAt" timestamp(3)
```

| Constraint / index | Evidence |
|---|---|
| Primary key `(id)` | `baseline:1408-1412` |
| **Unique constraints: none beyond the PK** | `schema.prisma:1491-1494` declares four `@@index` and zero `@@unique` |
| Index `(organizationId, projectId, status)` | `baseline:1870-1873` |
| Index `(organizationId, appointmentDate)` | `baseline:1849-1852` |
| Index `(organizationId, customerId)` | `baseline:1859` |
| Index `(organizationId, doctorId, appointmentDate)` | `baseline:1863-1866` |
| FK `createdById → users(id)` ON DELETE SET NULL | `baseline:2605-2606` |
| FK `customerId → customers(id)` ON DELETE SET NULL | `baseline:2613-2614` |
| FK `doctorId → doctors(id)` ON DELETE SET NULL | `baseline:2621-2622` |
| FK `procedureId → procedures(id)` ON DELETE SET NULL | `baseline:2629-2630` |
| **Four composite tenant FKs** added later: `(createdById, organizationId)`, `(customerId, organizationId)`, `(doctorId, organizationId)`, `(procedureId, organizationId)` | `V20261009110000__appointments_tenant_composite_fkeys.sql:292-312` |
| `status` is `text` with a default — **not** an enum, **not** a CHECK | `baseline:624`; `ClinicModels.java:132-138` documents why it stays text |
| `isAiProposed` defaults **`true`** | `baseline:627` |

`doctors` (`schema.prisma:1499-1521`) carries `workingDays Int[] DEFAULT [0,1,2,3,4]`,
`startHour String DEFAULT "09:00"`, `endHour String DEFAULT "17:00"`, `slotDuration Int DEFAULT 30`.
**These are strings and arrays, not typed time or calendar columns.**

`procedures` (`schema.prisma:1524-1542`) carries `durationMinutes Int DEFAULT 30`,
`bufferMinutes Int DEFAULT 0`, `price Float?`.

**No `tasks`, `waitlist`, `leave`, `holiday`, `resource`, `reminder`, `appointment_status_history`,
or clinic-holiday table exists.** The closest is `tasks` (`schema.prisma:1430-1457`), which is
entirely unclaimed by clinic code.

---

## 7. The double-booking question — answered from the schema

**Verdict: nothing in the current schema prevents a double booking. Double-booking is currently
impossible only because there is no write path at all.**

Evidence, all from files:

1. **No `EXCLUDE` constraint exists anywhere in the repository.** I searched every `.sql` file in
   the tree for the token. Every hit is either a comment, an English word, or an
   `ON CONFLICT ... DO UPDATE SET x = EXCLUDED.y` target. The only technical mention is
   `V20261005090000__finance_rate_cards_periods.sql:139` — a comment explaining that this project
   **deliberately avoided** needing one.
2. **`btree_gist` is never created.** Same search: only the same comment
   (`V20261005090000:139`) and one verification script comment
   (`ops/flyway/verification/V13_usage_finance_slice.sql:484`: "enforcing it in DDL needs
   `btree_gist` and an EXCLUDE constraint, which is a heavier dependency than the invariant").
   Without the extension, a GiST exclusion constraint on `(doctorId, tstzrange(...))` cannot be
   written at all.
3. **No trigger on `appointments`.** Searched every `.sql` for `TRIGGER` touching the table: zero
   hits. The triggers that do exist are on `api_keys`, `service_principals`, `usage_rollup_snapshots`,
   the `rate_card_versions`/`finance_periods`/`usage_events` append-only family
   (`V20261006120000:474,604,608`; `V20261005090000:969,1228-1255`).
4. **No unique index over doctor and time.** `appointments` has exactly four indexes
   (`baseline:1849-1873`), all btree, none unique on any time/doctor pair.
   The one unique-index family nearby is `sla_calendars` (`V20261005030000:484-518`), which is a
   different table and a different concern.
5. **`status` is unconstrained text** (`baseline:624`), so even "only non-cancelled rows conflict"
   is not expressible as a partial unique index today without a data migration.
6. **`appointmentDate` is `timestamp without time zone`** (`baseline:622`), not `timestamptz`.
   An exclusion constraint over a range on a zone-less timestamp encodes a wall-clock interval,
   not an instant. This is a real design decision Phase 5 must make, not an implementation detail.
7. **`durationMinutes` is a column on the appointment**, not a range or a generated expression
   (`baseline:623`). An exclusion constraint needs
   `tstzrange(appointmentDate, appointmentDate + durationMinutes * interval '1 minute')`, which is
   legal as an expression but means the constraint's correctness depends on a mutable column. An
   update to `appointmentDate` or `durationMinutes` re-evaluates it, which is what you want — but it
   must be designed, not bolted on.

**What the legacy code did instead, and why it is not enough:** a read-then-write. Both
implementations query existing appointments for the doctor and day, build `[start, start+duration]`
intervals in JavaScript, and test for overlap
(`scheduling-engine.service.ts:113-137,219-241`; `packages/ai/.../clinic-scheduling.ts:231-249,351-366`).
There is **no lock, no serializable transaction, no unique key, and no `SELECT … FOR UPDATE`**
anywhere in either path. Under two concurrent bookings both transactions read the same empty busy
set and both insert. This is a classic TOCTOU race.

**Therefore, for acceptance scenario 3 (two simultaneous bookings, exactly one succeeds):**

- Today it **cannot be exercised at all**, because there is no booking route.
- Porting the legacy code as-is would make it **actively fail**: two simultaneous requests would
  both succeed, which is worse than the current state because it would look like it works.
- The smallest missing piece is **not** a service method. It is, in order:
  1. a schema constraint that makes the conflict unrepresentable —
     `CREATE EXTENSION btree_gist` + an `EXCLUDE USING gist` on
     `(doctorId WITH =, tstzrange(appointmentDate, appointmentDate + durationMinutes * interval '1 minute') WITH &&)`
     restricted `WHERE (status NOT IN ('CANCELLED','NO_SHOW'))`, which requires first giving
     `status` a CHECK constraint or enum; **or**, if DDL is deferred, a
     `UNIQUE (doctorId, appointmentDate)` on a canonicalised slot key plus a per-doctor advisory
     lock (`pg_advisory_xact_lock(hashtext(doctorId))`) taken inside the write transaction — weaker
     (it does not handle overlapping-but-not-identical intervals) and it should be recorded as
     weaker;
  2. a decision on `timestamp` vs `timestamptz` and on the clinic timezone (there is **no** timezone
     column on `doctors`, `procedures` or `appointments`, and `Project.timezone` exists —
     `packages/ai/src/tools/executor.ts:144` reads it — but nothing in the clinic schema binds an
     appointment to it);
  3. the availability recheck **inside** the same transaction as the insert (plan §8 item 4), which
     the legacy code does not do;
  4. a concurrent-booking acceptance test. **I found none in the repository.**

---

## 8. Verdict on the 12 acceptance scenarios

`docs/clinic/requirements-traceability.md` §11 gives one line each. I re-checked each against the
source rather than restating it. **I agree with 9 of 12; three need sharpening, and one is more
negative than the table says.**

| # | Scenario | Can it pass today? | Smallest missing piece |
|---|---|---|---|
| 1 | Book an available slot; confirmation after save | **No.** No write route. **Additionally, the second half cannot pass even after booking exists:** the "confirmation after save" has no mechanism anywhere — there is no outbox enqueue on booking, no confirmation template, and no notification queue member (`QueueName.java:26-48`). | Booking route + service + insert + `APPOINTMENT_CREATE` + grant. The confirmation half belongs to plan §9 (Phase 6) and needs a transactional-outbox enqueue in the same transaction as the insert. |
| 2 | Conflicting slot rejected, no confirmation | **No**, and **the rejection is currently only an application-layer read**, not a constraint (§7). The "no confirmation" half is trivially satisfied today because nothing sends anything. | The availability engine + the DB constraint + rejection wired into the write path. |
| 3 | Two simultaneous bookings, exactly one succeeds | **No, and this is the worst row.** Double-booking is not merely unguarded — **nothing in the schema makes it impossible**, and the legacy algorithm is a TOCTOU read-then-write that would let **both** succeed (§7). The existing table's phrasing ("impossible only because booking is [unimplemented]") is right but understates it: the guard that would prevent it does not exist in any form. | `btree_gist` + an `EXCLUDE USING gist`, or a documented-weaker lock; a CHECK on `status`; availability recheck inside the write transaction; a concurrency acceptance test. |
| 4 | Slot outside hours or during leave never offered | **Partly, and the table is too pessimistic.** "Outside hours" is computable today from `doctors.workingDays` / `startHour` / `endHour`, and the legacy engine implements it (`scheduling-engine.service.ts:178-217`) — it just has no Spring home. **"During leave" is impossible**: there is no leave, holiday or unavailable-period table and no concept of one (traceability D-1, D-5; §6). | Port the hours engine; leave stays blocked on DEC-001 (and Phase 4 of the clinic plan, which owns leave). |
| 5 | Confirmation send fails; appointment stays, reception alerted | **No.** And this is **structurally impossible today in the opposite direction**: there is no send path at all, so the "failure honesty" half of the scenario is untested rather than satisfied. Note also that legacy "send" routes mark records `'SENT'`/`'DELIVERED'` without sending anything (`reminders.service.ts:161,231`) — porting that would make this scenario fail *while appearing to pass*. | Phase 6: real connector + delivery-state machine + an alert to reception on failure. |
| 6 | AI booking records source as AI, confirms after success | **No.** The only Spring tool **throws unconditionally** (`AppointmentProposalTool.java:101-106`). Its stated reason is ADR 0007 Part 2 — correct, and it should be preserved. **Note also that `isAiProposed` defaults to `true`** (`baseline:627`), so any row created without setting it looks AI-proposed. | A decided autonomy level (DEC-006), a route to approve a pending `ai_approvals` row (traceability AI-9 says that route does not exist), and booking performed **after** approval. |
| 7 | Duplicate warning, Arabic variants matched | **Partial, as stated — and this is the least appointment-related row.** The warning lives on `POST /customers/resolve-identity`, not on the appointment path; no `Normalizer` exists anywhere; `أحمد` ≠ `احمد` (traceability P-2). | Arabic normalisation, then the appointment create path must resolve a `Patient` (DEC-015) rather than a raw `customerId`. |
| 8 | Unauthorized user opens protected data → denied | **Yes.** This is the strongest property in the codebase and I re-verified it: byte-identical 404s for foreign / nonexistent / left-project (`ClinicReadModelHttpIntegrationTest.java:552`), 403 for a missing grant (`:745`), 401 unauthenticated (`:692`), and no-membership returns nothing (`:644`). | Nothing. Preserve these four tests verbatim when the write path lands — a new write surface must not weaken them. |
| 9 | Cancellation records status and reason, frees the slot | **No.** No cancel path. Also note the legacy behaviour to decide explicitly: status is set and the reason is **appended to the free-text `notes` column** (`local-clinic.adapter.ts:848-854`), which the Spring read model deliberately does not project (`ClinicModels.java:24-27`). "Frees the slot" is implicit — cancellation works by being excluded from the availability query (`local-clinic.adapter.ts:725-727`), not by freeing anything. | Cancel route + service + a structured reason field (a schema decision, not `notes`) + the availability engine so "frees the slot" is actually testable. |
| 10 | Handoff with context summary and SLA clock | **Yes for conversations** (traceability AI-9), **no for appointments** — and I found no appointment handoff of any kind in either runtime. The legacy simulator *narrates* a handoff in prose (`ai-simulator.service.ts:473-478`) without performing one. | Out of Phase 5 unless an appointment handoff is explicitly wanted. |
| 11 | Opted-out patient receives nothing | **No.** No consent column or table exists anywhere (traceability P-5, R-5). The outbound pre-flight has three checks and none is consent (`OutboundGuard.java:40` per traceability — I did not re-read that file; recorded as unverified). | DEC-005 + a consent model. Phase 6. |
| 12 | Outside the 24h window, only approved templates | **Partial, as stated.** The window guard is real (`ConnectorCatalog.java:34`; `OutboundDeliveryService.java:191` per traceability — unverified by me). Templates cannot be sent at all: the connector is `sendText` only with no template name or parameters on the wire (traceability R-6). | Phase 6 + DEC-005. Independent of appointments. |

**Net:** 1 passes (8), 4 are partial in a way the table does not say (4, 6, 7, 12), 7 fail. Scenario 3
is the one that must not be attempted without schema work.

---

## 9. Hidden dependencies — what legacy handlers rely on that Spring does not have

| Dependency | Legacy | Spring | Impact on the port |
|---|---|---|---|
| **A durable queue for reminders** | none — `runBatchReminders` is a synchronous HTTP call over an in-memory `Map` (`reminders.service.ts:190-245`) | `QueueName` has **no reminder member** (`QueueName.java:26-48`) | Reminders cannot be ported as scheduled behaviour. A `CLINIC_REMINDER` queue must be added, and `QueueName` is a closed enum by design (`:7-14`). |
| **A "publish at T" primitive** | n/a | **Absent.** `OutboxRepository.ENQUEUE` hardcodes `nextAttemptAt = now()` (`OutboxRepository.java:53`). `nextAttemptAt` is only ever moved by the **retry** path (`:110`). | A 24h-before reminder cannot be scheduled through the outbox as it stands. **This is the concrete blocker for plan §9 items 1–2, and it is a schema/API change, not a service method.** |
| **A channel connector for confirmations/reminders** | `reminders.service.ts` calls **no** connector at all | `backend/channels` exists with `MessagingConnector.sendText` (traceability R-6) | Phase 6. |
| **A cron/scheduler** | none | none. Zero `@Scheduled`/`@Cron` in production (traceability §4). | "Reminders send automatically" cannot be built on either runtime as they stand. |
| **An outbox enqueue on booking** | none | `OutboxRepository` exists, unused by clinic | Phase 5 must enqueue `appointment.created` in the same transaction if Phase 6 is to work. Plan §9 item 1 requires exactly this. |
| **Idempotency** | none on any clinic write | `IdempotencyRepository.reserve` exists (`IdempotencyRepository.java:125`), unused by clinic | Acceptance test "idempotency" (`firstelement-clinic-appointments.md:22`) has a mechanism available and unused. |
| **A patient/identity model** | `customers` + optional `customerId` | same, but the clinic projection **drops `customerId`** | Phase 5 booking has nowhere to link a patient except free-text `patientName`/`patientPhone`. Depends on DEC-015. |
| **`Doctor` ↔ authorised user link** | absent | absent | Plan §7 item 1. Booking records `createdById` but the clinician has no user row, so "assigned doctor" cannot be enforced. |
| **Leave / holidays / clinic entity / timezone** | absent | absent | `Project.timezone` exists and is read by the AI executor (`packages/ai/src/tools/executor.ts:144`) but **nothing binds an appointment to it**. DEC-001 blocks. |
| **Consent** | absent | absent | DEC-005. |
| **The AI orchestrator + tool executor** | `packages/ai` — 8 clinic tools, real booking | `backend/ai-rag` — 2 tools, one of which throws | See §5. Port the *logic*, not the execution path. |
| **Frontend consumers** | `apps/web` calls `/clinic/doctors`, `/clinic/procedures`, `/clinic/slots` (`components/appointment-form.tsx:112,117,180`), `/clinic/analytics` (`routes/DashboardPage.tsx:51`), `/clinic/reminders*` (`lib/reminders.ts:36,46,59,74`), `/clinic/ai-simulator/chat` (`lib/clinic-ai.ts:96`), `/clinic/doctors/manage` + roster mutations (`routes/DoctorSchedulesPage.tsx:118-154,901-1094`) | n/a | Seven of the 22 legacy routes have a live UI. **Notably: `POST /clinic/appointments` has no frontend caller at all** (searched all of `apps/web/**/*.tsx` — zero matches). The legacy booking form computes slots and then does not book. |
| **In-memory state** | `RemindersService.remindersStore` (`reminders.service.ts:46`) and its three seeded demo patients (`:56-76`) | none | **Do not port.** Any reminder port must be table-backed; porting this would seed fabricated patients into a production screen. |

---

## 10. Port ordering recommendation (read-only opinion; the orchestrator decides)

1. **Decide the three conflicts in §5 before writing any code**: slot step, buffer, resulting status.
2. **DEC-001 / DEC-002 / DEC-003 gate the schema.** Without DEC-001 there is no defensible duration
   rule; without DEC-003 there is no defensible status set or CHECK constraint; DEC-002 determines
   whether an exclusion constraint keys on doctor alone or on a resource.
3. **Schema first:** `btree_gist` + the `EXCLUDE` constraint (or the documented-weaker lock),
   a CHECK on `status`, `timestamp` vs `timestamptz`, and a structured cancellation-reason column.
   Nothing else in this plan survives without these.
4. **Availability service** (one implementation, consumed by UI, AI, reminders and reports — plan
   §7 item 5), with `schedule:read-availability` added to `Permission`.
5. **Booking / reschedule / cancel** with `APPOINTMENT_CREATE/RESCHEDULE/CANCEL` added to
   `Permission` and to `RolePermissions` for `CLINIC_STAFF`, an audit row in the same transaction,
   and an `Idempotency-Key` on `POST`.
6. **Roster/catalogue writes** only after a review sized for a mutation surface (the legacy `POST
   /clinic/doctors` is the documented breach origin, `ClinicController.java:41-46`).
7. **Phase 6** for reminders, confirmations and consent, gated on DEC-005 and on the outbox
   gaining a "publish at T".

---

## 11. Known risks (reported, not fixed)

| Risk | Where | Why it matters |
|---|---|---|
| **The legacy booking path is not transactional.** The appointment is written, then the audit row, in two separate calls. A crash between them leaves an unaudited clinical write. | `clinic.service.ts:128` then `:149`; same shape in `reschedule` (`:207,210`) and `cancel` (`:238,240`) | This is precisely the evidence gap Phase 26 hit. **Porting it reproduces it.** |
| **Audit `actorType` is `'AI'` for human-driven reschedule and cancel.** | `clinic.service.ts:215` and `:245` | An audit row asserting a human action was an AI action. ADR 0010 §2. |
| **Two divergent availability implementations already exist** (slot step, buffer, status, `isAiProposed`). | §5 | A third would make "is this slot free?" unanswerable. |
| **The legacy AI path books directly from a model turn with no approval.** | `packages/ai/src/tools/clinic-scheduling.ts:512`, reached via `webhooks.controller.ts:55` | If the legacy runtime is still deployed and reachable, ADR 0007 Part 2 is not enforced on this path. Not a Phase 5 defect; a live-system question for the orchestrator. |
| **Two Spring javadoc references name test classes that do not exist.** | `ClinicController.java:301`, `ClinicReadService.java:32` | Misleads the next reader hunting for the evidence. |
| **`ClinicModels.Appointment` is a record and the module is read-only by construction.** | `ClinicModels.java:46-51` | Adding a setter-like mutation later bypasses the stated safety argument. Keep writes in a separate write model. |
| **`remindersStore` seeds three fabricated patients.** | `reminders.service.ts:56-76` | Harmless today (in-memory). A careless port into a table would create fake patient records. |
| **The legacy `GET /clinic/procedures/:id/duration` converts "not visible" into `30`.** | `clinic.service.ts:52-55` | An existence oracle in the opposite direction, and a wrong duration. |
| **`isAiProposed DEFAULT true`** | `baseline:627` | Any row inserted without an explicit value claims AI provenance. `open-decisions.md:78-80` already flags this. |

## 12. Follow-up for orchestrator

1. Correct "21 handlers" → **22 non-public + 2 public** in `ClinicController.java:40`,
   `requirements-traceability.md:55`, `open-decisions.md:140`. (Not my files.)
2. **Decide before coding:** slot step, buffer inclusion, and the status an AI-approved booking lands
   in (§5). These are three product answers, not three defaults.
3. **DEC-003 is on the critical path for the schema**, not just for notifications — a CHECK
   constraint on `status` cannot be written until the status set is approved, and scenario 3 cannot
   pass until the exclusion constraint exists.
4. **The outbox has no "publish at T".** `OutboxRepository.ENQUEUE` hardcodes `now()`
   (`OutboxRepository.java:53`). Plan §9's "enqueue confirmation" is fine; §9's "reminder schedules"
   is blocked until this changes.
5. `GET /clinic/analytics` legacy payload should be recorded as **DROP**, with the fabricated-series
   evidence (`clinic.service.ts:277-281,376-380,408-414,428-429,443-447`) cited, so it is a decision
   on the record rather than a silent omission.
6. `POST /clinic/ai-simulator/chat` should be recorded as **DROP**, and its booking behaviour
   (`ai-simulator.service.ts:371-382`) checked against ADR-0007 Part 2 before cutover.
7. **The `packages/ai` booking path books with no human approval � and the condition is not
   hypothetical.** The chain is
   `webhooks.controller.ts:55 -> message-queue.service.ts:103 -> orchestrator.ts:78 -> executor.ts:295 -> clinic-scheduling.ts:512`,
   which creates the appointment with `status: 'APPROVED'` hardcoded (L588). Verified 2026-10-13
   by the orchestrator: `deploy/fly/Dockerfile` is a **Node 22** image whose CMD is
   `node apps/api/dist/main.js & node apps/worker/dist/main.js`, and there is **no Java or Spring
   entrypoint anywhere under `deploy/`**. The legacy runtime is not "possibly deployed" � it is
   this repository's only deployment configuration. An explicit ruling is required (P1 remove /
   P2 approval gate / P3 written acceptance); engineering recommends P1. Tracked in
   `docs/migration/phases/phase-12-dual-run-rehearsal.md`.
8. There is **no concurrency acceptance test anywhere in the repository**. Writing one should be a
   Phase 5 exit criterion, not an afterthought.

---

*Inventory only. One new file created; nothing else modified. No build, test, database or network
command was run.*

   creates the appointment with `status: 'APPROVED'` hardcoded (L588). Verified 2026-10-13 by the orchestrator: `deploy/fly/Dockerfile` is a **Node 22** image whose CMD is `node apps/api/dist/main.js & node apps/worker/dist/main.js`, and there is **no Java or Spring entrypoint anywhere under `deploy/`**. The legacy runtime is not "possibly deployed" -- it is this repository's only deployment configuration. Ruling required (P1 remove / P2 approval gate / P3 written acceptance); engineering recommends P1. Tracked in `docs/migration/phases/phase-12-dual-run-rehearsal.md`.
