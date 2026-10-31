# FirstElement — Current System Inventory

Baseline: `clinic-flow-main` @ working tree inspected 2026-10-04. Source of
truth is the code, cross-checked against `PROJECT_REQUIREMENTS.md`,
`plan.md`, `PR.md`, `README.md`, `CRM_FEATURES.md`, `FE-CRM-V1.2.md`,
`docs/*.md`, and `docs/opencode-building-notes.md`.

Validation status (recorded as gates, not passes):

- `yarn test` = 238 passing, 41 skipped. Integration suites skip when
  `DATABASE_URL` is absent (recorded as FAIL evidence per Phase 0 gate).
- `yarn typecheck` fails: 3 implicit-`any` parameters in
  `apps/api/src/main.ts` (the production HTTPS redirect middleware,
  `(req, res, next)` on lines 23–29).
- `yarn lint` fails: 118 errors / 39 warnings (unused imports/vars,
  untyped/unused parameters, CommonJS script linting).
- Docker unavailable in inspection sandbox; PostgreSQL-backed suites
  could not be executed here.
- Prisma schema vs initial migration drift: `EmployeeAvailabilityStatus`
  enum is `ONLINE, OFFLINE, BUSY, BREAK, MEAL, TRAINING, MEETING` in
  `schema.prisma` but `AVAILABLE, BUSY, AWAY, OFFLINE` in
  `20260814000000_init/migration.sql`. Also `AIRunDecision` in the schema
  includes `RESOLVE`, while the migration omits it. Do not baseline
  Flyway until reconciled.

---

## 1. API endpoints (method, path, controller, permission)

Global prefix `/api`. Guard order: `AuthGuard` → `PermissionsGuard` →
`ThrottlerGuard` (default 300/min). All endpoints require a session cookie
unless marked `@Public()`. `@RequirePermissions(...)` maps to
`packages/auth/src/policy.ts` Permission values.

### Auth (`auth.controller.ts`)

| Method | Path | Permission / guard |
| --- | --- | --- |
| POST | `/auth/login` | `@Public()`, throttle 10/min |
| POST | `/auth/logout` | session required |
| GET | `/auth/me` | session required |

Session: opaque token in `SESSION_COOKIE_NAME` httpOnly cookie;
`Session` row stores SHA-256 `tokenHash`, userAgent, ipAddress,
expiresAt, revokedAt, lastSeenAt. Cookie options from
`sessionCookieOptions` in `packages/auth/src/session-token.ts`
(`secure` in production, TTL from `SESSION_TTL_HOURS`).

### Organization (`organizations.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/organization` | `ORGANIZATION_READ` |
| PATCH | `/organization` | `ORGANIZATION_UPDATE` |

### Projects (`projects.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/projects` | `PROJECT_READ` |
| GET | `/projects/:projectId` | `PROJECT_READ` |
| POST | `/projects` | `PROJECT_CREATE` |
| PATCH | `/projects/:projectId` | `PROJECT_UPDATE` |
| DELETE | `/projects/:projectId` | `PROJECT_DELETE` |

### Employees (`employees.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/employees` | `EMPLOYEE_READ` |
| PATCH | `/employees/me/availability` | `EMPLOYEE_SET_OWN_AVAILABILITY` |
| GET | `/employees/:userId` | `EMPLOYEE_READ` |
| POST | `/employees` | `EMPLOYEE_CREATE` |
| PATCH | `/employees/:userId` | `EMPLOYEE_UPDATE` |
| DELETE | `/employees/:userId` | `EMPLOYEE_DELETE` |

### Routing / agent status / employee import (`routing.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/employees/routing` | `EMPLOYEE_READ` |
| PATCH | `/me/availability` | `EMPLOYEE_SET_OWN_AVAILABILITY` |
| GET | `/me/status` | `EMPLOYEE_SET_OWN_AVAILABILITY` |
| GET | `/me/status/totals` | `EMPLOYEE_SET_OWN_AVAILABILITY` |
| GET | `/employees/:employeeId/status/totals` | `EMPLOYEE_READ` |
| PATCH | `/employees/:employeeId/status` | `EMPLOYEE_UPDATE` |
| PUT | `/employees/:employeeId/working-hours` | `EMPLOYEE_UPDATE` |
| PUT | `/employees/:employeeId/skills` | `EMPLOYEE_UPDATE` |
| GET | `/projects/:projectId/routing-rules` | `EMPLOYEE_READ` |
| POST | `/projects/:projectId/routing-rules` | `ROUTING_RULE_MANAGE` |
| PUT | `/projects/:projectId/routing-rules/:ruleId` | `ROUTING_RULE_MANAGE` |
| DELETE | `/projects/:projectId/routing-rules/:ruleId` | `ROUTING_RULE_MANAGE` |
| POST | `/employees/import/preview` | `EMPLOYEE_IMPORT` (multipart CSV/XLSX, 5MB) |
| POST | `/employees/import` | `EMPLOYEE_IMPORT` |

### Customers (`customers.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/customers` | `CUSTOMER_READ` |
| GET | `/customers/:customerId` | `CUSTOMER_READ` |
| GET | `/customers/cx/:cxId` | `CUSTOMER_READ` |
| PATCH | `/customers/:customerId` | `CUSTOMER_UPDATE` |

### Conversations (`conversations.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/conversations` (inbox query) | `CONVERSATION_READ` |
| GET | `/conversations/:conversationId` | `CONVERSATION_READ` |
| POST | `/conversations/:conversationId/messages` | `CONVERSATION_REPLY` |
| PATCH | `/conversations/:conversationId/assignment` | `CONVERSATION_ASSIGN` |
| PATCH | `/conversations/:conversationId` | `CONVERSATION_RESOLVE` |
| PATCH | `/conversations/:conversationId/close` | `CONVERSATION_RESOLVE` |

### Accounts / Notes / Views / Leads / Deals / Tasks (CRM middle workspace)

Accounts (`accounts.controller.ts`): GET `/accounts`, GET `/accounts/:id`
(`ACCOUNT_READ`); POST `/accounts` (`ACCOUNT_CREATE`); PATCH `/accounts/:id`
(`ACCOUNT_UPDATE`); DELETE `/accounts/:id` (`ACCOUNT_DELETE`).

Notes (`notes.controller.ts`): GET `/notes` (`NOTE_READ`, `resourceType`/`resourceId` query); GET `/notes/project` (`NOTE_READ`); POST `/notes` (`NOTE_CREATE`); PATCH `/notes/:id` (`NOTE_UPDATE`); DELETE `/notes/:id` (`NOTE_DELETE`).

Views (`views.controller.ts`): GET `/objects/:objectType/views` (`VIEW_READ`); POST `/objects/:objectType/views` (`VIEW_MANAGE`); PATCH `/views/:id` (`VIEW_MANAGE`); DELETE `/views/:id` (`VIEW_MANAGE`).

Leads (`leads.controller.ts`, dual route `['leads','follow-ups']`): GET `/leads` & `/follow-ups` (`LEAD_READ`); GET `/:id` (`LEAD_READ`); POST `/` (`LEAD_CREATE`); PATCH `/:id` (`LEAD_UPDATE`); POST `/:id/convert` (`LEAD_CONVERT`); DELETE `/:id` (`LEAD_DELETE`).

Deals (`deals.controller.ts`): GET `/deals`, GET `/deals/:id` (`DEAL_READ`); POST `/deals` (`DEAL_CREATE`); PATCH `/deals/:id` (`DEAL_UPDATE`); POST `/deals/:id/close` (`DEAL_CLOSE`); DELETE `/deals/:id` (`DEAL_DELETE`).

Tasks (`tasks.controller.ts`): GET `/tasks`, GET `/tasks/:id` (`TASK_READ`); POST `/tasks` (`TASK_CREATE`); PATCH `/tasks/:id` (`TASK_UPDATE`); DELETE `/tasks/:id` (`TASK_DELETE`).

### Cases (`cases.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/projects/:projectId/classification-questions` | `CONVERSATION_READ` |
| POST | `/projects/:projectId/classification-questions` | `ESCALATION_RULE_MANAGE` |
| PUT | `/projects/:projectId/classification-questions/:questionId` | `ESCALATION_RULE_MANAGE` |
| DELETE | `/projects/:projectId/classification-questions/:questionId` | `ESCALATION_RULE_MANAGE` |
| GET | `/conversations/:conversationId/classification` | `CONVERSATION_READ` |
| PUT | `/conversations/:conversationId/classification` | `CONVERSATION_REPLY` |
| GET | `/conversations/:conversationId/resolution` | `CONVERSATION_READ` |
| PUT | `/conversations/:conversationId/resolution` | `CONVERSATION_RESOLVE` |
| POST | `/conversations/:conversationId/close` | `CONVERSATION_RESOLVE` |
| POST | `/conversations/:conversationId/reopen` | `CONVERSATION_RESOLVE` |

### Channels (`channels.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/projects/:projectId/channels` | `CHANNEL_READ` |
| POST | `/projects/:projectId/channels` | `CHANNEL_MANAGE` |
| PUT | `/projects/:projectId/channels/:connectionId/credentials` | `CHANNEL_MANAGE` |
| PATCH | `/projects/:projectId/channels/:connectionId` | `CHANNEL_MANAGE` |

### Webhooks (`webhooks.controller.ts`, all `@Public()`)

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/webhooks/meta/:connectionId` | Meta verify handshake, constant-time token compare, throttle 30/min |
| POST | `/webhooks/meta/:connectionId` | Meta signature verify (HMAC over raw body), throttle 600/min |
| POST | `/webhooks/telegram/:connectionId` | Telegram secret token header, throttle 600/min |

All return `{ received: true }` (200 on verified requests even when
processing fails; durability belongs to the queue).

### Mock inbound simulator (`mock-webhook.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| POST | `/dev/mock/incoming-message` | `CONVERSATION_REPLY`; 404 unless `ENABLE_MOCK_CONNECTOR` (config refuses flag in production) |

### Knowledge (`knowledge.controller.ts`) — all under `/projects/:projectId/knowledge`

| Method | Path | Permission |
| --- | --- | --- |
| GET | `bases` | `KNOWLEDGE_READ` |
| POST | `bases` | `KNOWLEDGE_MANAGE` |
| GET | `documents` | `KNOWLEDGE_READ` |
| POST | `bases/:knowledgeBaseId/documents` | `KNOWLEDGE_MANAGE` (multipart, `MAX_UPLOAD_BYTES` 20MB) |
| POST | `bases/:knowledgeBaseId/text` | `KNOWLEDGE_MANAGE` |
| POST | `documents/:documentId/reprocess` | `KNOWLEDGE_MANAGE` |
| DELETE | `documents/:documentId` | `KNOWLEDGE_MANAGE` |
| POST | `search` | `KNOWLEDGE_READ` |

### AI (`agents.controller.ts`, `ai-runs.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/projects/:projectId/agent` | `AI_AGENT_READ` |
| GET | `/projects/:projectId/agent/versions` | `AI_AGENT_READ` |
| PUT | `/projects/:projectId/agent` | `AI_AGENT_CONFIGURE` (appends a version) |
| POST | `/projects/:projectId/agent/versions/:versionId/activate` | `AI_AGENT_CONFIGURE` |
| GET | `/ai-runs` | `AI_RUN_READ` |
| GET | `/ai-runs/:runId` | `AI_RUN_READ` |

### Escalation (`escalation.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/projects/:projectId/escalation-rules` | `ESCALATION_RULE_READ` |
| POST | `/projects/:projectId/escalation-rules` | `ESCALATION_RULE_MANAGE` |
| PUT | `/projects/:projectId/escalation-rules/:ruleId` | `ESCALATION_RULE_MANAGE` |
| DELETE | `/projects/:projectId/escalation-rules/:ruleId` | `ESCALATION_RULE_MANAGE` |
| GET | `/conversations/:conversationId/scores` | `CONVERSATION_READ` |
| GET | `/conversations/:conversationId/escalations` | `CONVERSATION_READ` |

### Notifications + realtime (`notifications.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/notifications` | `PROJECT_READ` |
| GET | `/notifications/unread-count` | `PROJECT_READ` |
| POST | `/notifications/:notificationId/read` | `PROJECT_READ` |
| POST | `/notifications/read-all` | `PROJECT_READ` |
| SSE | `/realtime/stream` | `PROJECT_READ` |

### Analytics (`analytics.controller.ts`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/me/analytics` | self-service (own employee) |
| GET | `/analytics/summary` | `ANALYTICS_READ_ORGANIZATION` |
| GET | `/projects/:projectId/analytics` | `ANALYTICS_READ_PROJECT` |
| GET | `/employees/:employeeId/analytics` | `ANALYTICS_READ_ORGANIZATION` |
| GET | `/audit-logs` | `AUDIT_LOG_READ` |
| GET | `/escalations/unassigned` | `CONVERSATION_READ` |

### Health (`health.controller.ts`, `system-health.controller.ts`)

| Method | Path | Guard |
| --- | --- | --- |
| GET | `/health` | `@Public()` — bare up/down + database check |
| GET | `/system/health` | `SYSTEM_HEALTH_READ` — env, db/redis latency, queue depths, provider config presence, stalled documents |

### Appointments (`appointments.controller.ts`)

| Method | Path | Guard |
| --- | --- | --- |
| GET | `/appointments` | `AuthGuard` only (no permission decorator) |
| GET | `/appointments/:id` | `AuthGuard` |
| POST | `/appointments` | `AuthGuard` |
| PATCH | `/appointments/:id` | `AuthGuard` |
| DELETE | `/appointments/:id` | `AuthGuard` |

### Clinic (`clinic.controller.ts`, `public-appointment.controller.ts`)

`ClinicController` uses `AuthGuard` only — **no `@RequirePermissions`
decorators anywhere in the clinic module** (noted as a security gap for
Agent C).

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/clinic/doctors` | query projectId |
| GET | `/clinic/doctors/manage` | |
| POST | `/clinic/doctors` | body untyped (`any`) |
| PATCH | `/clinic/doctors/:id` | body untyped |
| DELETE | `/clinic/doctors/:id` | |
| GET | `/clinic/procedures` | |
| GET | `/clinic/procedures/manage` | |
| POST | `/clinic/procedures` | body untyped |
| PATCH | `/clinic/procedures/:id` | body untyped |
| DELETE | `/clinic/procedures/:id` | |
| GET | `/clinic/procedures/:id/duration` | |
| GET | `/clinic/slots` | doctorId/procedureId/date |
| GET | `/clinic/check-availability` | doctorId/procedureId/dateTime |
| POST | `/clinic/appointments` | BookingRequestInput |
| PATCH | `/clinic/appointments/:id/reschedule` | newDate/newDateTime |
| POST | `/clinic/appointments/:id/cancel` | reason |
| POST | `/clinic/ai-simulator/chat` | body untyped |
| GET | `/clinic/reminders` | reminder summary |
| POST | `/clinic/reminders/send-batch` | |
| POST | `/clinic/reminders/send/:appointmentId` | channel WHATSAPP/SMS |
| POST | `/clinic/reminders/respond` | legacy patient-confirm path (no tenant guard; uses appointmentId in body) |
| GET | `/clinic/analytics` | days param, executive analytics |

Public: GET `/public/appointments/:token`, POST
`/public/appointments/:token/respond` (`@Public()`).

### Permission inventory (from `packages/auth/src/policy.ts`)

`ORGANIZATION_READ`, `ORGANIZATION_UPDATE`, `PROJECT_READ`,
`PROJECT_CREATE`, `PROJECT_UPDATE`, `PROJECT_DELETE`,
`PROJECT_ARCHIVE` (declared, not used on any controller),
`EMPLOYEE_READ`, `EMPLOYEE_CREATE`, `EMPLOYEE_UPDATE`,
`EMPLOYEE_DELETE`, `EMPLOYEE_IMPORT`, `EMPLOYEE_SET_OWN_AVAILABILITY`,
`CUSTOMER_READ`, `CUSTOMER_UPDATE`, `ACCOUNT_READ/CREATE/UPDATE/DELETE`,
`NOTE_READ/CREATE/UPDATE/DELETE`, `VIEW_READ/MANAGE`,
`LEAD_READ/CREATE/UPDATE/CONVERT/DELETE`,
`DEAL_READ/CREATE/UPDATE/CLOSE/DELETE`,
`TASK_READ/CREATE/UPDATE/DELETE`, `CONVERSATION_READ/REPLY/TAKE_OVER/
RETURN_TO_AI/ASSIGN/RESOLVE` (TAKE_OVER/RETURN_TO_AI declared; the
take-over handler lives in the messaging flow, not a dedicated route),
`AI_AGENT_READ/CONFIGURE`, `AI_RUN_READ`, `KNOWLEDGE_READ/MANAGE`,
`CHANNEL_READ/MANAGE`, `ESCALATION_RULE_READ/MANAGE`,
`ROUTING_RULE_MANAGE`, `ANALYTICS_READ_PROJECT/ORGANIZATION`,
`AUDIT_LOG_READ`, `SYSTEM_HEALTH_READ`.

Two roles: `ADMIN` (all) and `EMPLOYEE` (subset listed in
`EMPLOYEE_PERMISSIONS`; self-service availability-status permissions are
owner-scoped via `resource.ownerUserId`).

---

## 2. Prisma models, enums, indexes, FKs, unique constraints, migrations

File: `packages/database/prisma/schema.prisma` (≈1535 lines). Provider
`postgresql`, extensions `pgcrypto`, `vector`. All models use `@@map` to
snake_case table names (e.g. `ai_runs`, `document_chunks`), columns are
camelCase-quoted in the DB via Prisma defaults (note: `CREATE TABLE`
identifiers in the init migration are camelCase-quoted, e.g.
`"organizationId"`).

### Enums (schema vs init-migration drift flagged)

- `UserRole`: `ADMIN`, `EMPLOYEE`
- `Locale`: `ar`, `en`
- `EmployeeAvailabilityStatus`: schema `ONLINE, OFFLINE, BUSY, BREAK,
  MEAL, TRAINING, MEETING` vs migration `AVAILABLE, BUSY, AWAY, OFFLINE`
  — **drift, must reconcile before Flyway baseline**.
- `ChannelType`: `MOCK, WHATSAPP, INSTAGRAM, MESSENGER, TELEGRAM`
- `ConversationStatus`: `OPEN, PENDING, RESOLVED, CLOSED`
- `ConversationMode`: `AI, HUMAN`
- `ConversationPriority`: `LOW, NORMAL, HIGH, URGENT`
- `MessageDirection`: `INBOUND, OUTBOUND`
- `MessageAuthorType`: `CUSTOMER, EMPLOYEE, AI, SYSTEM`
- `MessageDeliveryStatus`: `PENDING, SENT, DELIVERED, READ, FAILED`
- `ConversationEventType`: `CONVERSATION_CREATED, MESSAGE_SENT,
  MESSAGE_RECEIVED, ASSIGNED, UNASSIGNED, TAKEN_OVER, RETURNED_TO_AI,
  ESCALATED, SCORE_UPDATED, STATUS_CHANGED, PRIORITY_CHANGED, TAG_ADDED,
  NOTE_ADDED`
- `RoutingStrategy`: `LEAST_LOADED, ROUND_ROBIN`
- `EscalationStatus`: `PENDING, ASSIGNED, RESOLVED, CANCELLED`
- `EscalationTrigger`: `RULE, EXPLICIT_REQUEST, AI_FAILURE,
  LOW_CONFIDENCE, MANUAL`
- `AIProviderKind`: `MOCK, OPENAI, ANTHROPIC, GEMINI,
  OPENAI_COMPATIBLE, LOCAL`
- `AIRunStatus`: `RUNNING, COMPLETED, FAILED, ABORTED`
- `AIRunStepKind`: `CONTEXT, RETRIEVAL, TOOL_EXECUTION, GENERATION,
  EVALUATION, DECISION`
- `AIRunDecision`: schema includes `RESOLVE`; init migration omits it —
  **drift**.
- `DocumentStatus`: `UPLOADED, PROCESSING, INDEXING, READY, FAILED`
- `DocumentSourceType`: `FILE, URL, TEXT`
- `ChannelConnectionStatus`: `DISCONNECTED, CONNECTED, ERROR`
- `CaseOutcome`: `RESOLVED, ESCALATED, UNRESOLVED`
- `NotificationType`: `CONVERSATION_ASSIGNED,
  CONVERSATION_ESCALATED, CONVERSATION_UNASSIGNED_QUEUE, MENTION, SYSTEM`
- Missing in the initial migration (added later by `db push` drift or
  missing SQL): `CaseSolutionMapping`, several V1.2 fields — verify with
  Agent B. The init migration SQL should be re-derived from the live DB.

### Models (table map via `@@map`)

`Organization`→`organizations`; `Project`→`projects`;
`User`→`users`; `Employee`→`employees`;
`ProjectMembership`→`project_memberships`; `Session`→`sessions`;
`AuditLog`→`audit_logs`; `Customer`→`customers`; `Lead` (no `@@map`,
table `Lead`); `Deal`→`deals`; `CustomerIdentity`→`customer_identities`;
`Account`→`accounts`; `Note`→`notes`; `View`→`views`;
`Conversation`→`conversations`; `Message`→`messages`;
`ChannelConnection`→`channel_connections`;
`CaseClassificationQuestion`→`case_classification_questions`;
`ConversationClassification`→`conversation_classifications`;
`CaseSolutionMapping`→`case_solution_mappings`;
`CaseResolution`→`case_resolutions`; `Notification`→`notifications`;
`AgentStatusSession`→`agent_status_sessions`;
`EmployeeSkill`→`employee_skills`;
`EmployeeAvailability`→`employee_availability`;
`RoutingRule`→`routing_rules`; `ConversationScore`→`conversation_scores`;
`EscalationRule`→`escalation_rules`; `Escalation`→`escalations`;
`AIAgent`→`ai_agents`; `AIAgentVersion`→`ai_agent_versions`;
`AIRun`→`ai_runs`; `AIRunStep`→`ai_run_steps`;
`AIRunRetrieval`→`ai_run_retrievals`;
`AIRunToolCall`→`ai_run_tool_calls`; `AIUsage`→`ai_usage`;
`KnowledgeBase`→`knowledge_bases`; `Document`→`documents`;
`DocumentChunk`→`document_chunks`; `KnowledgeGap`→`knowledge_gaps`;
`ConversationEvent`→`conversation_events`; `Task`→`tasks`;
`Appointment`→`appointments`; `Doctor`→`doctors`;
`Procedure`→`procedures`.

Key unique constraints: `Organization.slug`; `Project(organizationId,
slug)`; `User.email`; `Employee.userId`; `ProjectMembership(projectId,
userId)`; `Session.tokenHash`; `Customer.cxId`;
`CustomerIdentity(projectId, platform, externalId)`;
`Account(organizationId, name)`; `Conversation.ticketNumber`;
`Message(projectId, channel, externalMessageId)`;
`ChannelConnection(projectId, channel)`;
`CaseClassificationQuestion(projectId, position)`;
`ConversationClassification.conversationId`;
`CaseResolution.conversationId`; `AgentStatusSession` none (index only);
`EmployeeSkill(employeeId, skill)`;
`EmployeeAvailability(employeeId, dayOfWeek, startMinute)`;
`AIAgent.projectId`; `AIAgent.activeVersionId`;
`AIAgentVersion(agentId, version)`; `AIRunUsage.runId`
(`AIUsage.runId` unique); `AIRunStep(runId, sequence)`;
`AIRunToolCall(runId, sequence)`; `KnowledgeBase(projectId, name)`;
`DocumentChunk(documentId, chunkIndex)`; `Task(projectId, title)`.

Notable indexes (all lead with organizationId/projectId per tenancy
rule): conversations `(organizationId, projectId, lastMessageAt)` +
four additional status/mode/channel/assignee composites, plus
`(projectId, customerId, lastMessageAt)`; messages
`(organizationId, projectId, conversationId, sentAt)`,
`(conversationId, sentAt)`; knowledge `(organizationId, projectId,
status)`, `(knowledgeBaseId, createdAt)`, `(projectId, contentHash)`;
embedding HNSW index `document_chunks_embedding_hnsw_idx` (hand-written
in migration 20260814000100) and tenant prefilter index
`document_chunks_tenant_idx`; sessions `(userId, expiresAt)`,
`(expiresAt)`; audit logs three composites; AI run/usage/retrieval/tool
indexes as listed in schema.

Foreign keys: all relations listed in schema with `onDelete` actions
(Cascade for project-scoped children, SetNull for optional owners,
Restrict for `AIRun.agentVersion`). `Lead`/`Deal`/`Customer` reference
`Account` and `User` with SetNull; `Appointment` references
`Customer`/`Doctor`/`Procedure` with SetNull; `Task` references only
`createdBy` (customer/lead/deal/assignedTo stored as plain strings, not
FKs — **debt for Agent B to note**).

### Migrations

1. `20260814000000_init/migration.sql` — full schema DDL (enums,
   tables, FKs, uniques, indexes). **Drift vs schema flagged above.**
2. `20260814000100_vector_index/migration.sql` — hand-written HNSW
   cosine index on `document_chunks."embedding"` + tenant prefilter
   index.

`prisma migrate` history table not inspected (no live DB); the above is
the on-disk history.

---

## 3. React frontend

Stack: React 18 + Vite + react-router-dom v6 + @tanstack/react-query +
Jotai (in deps; session state lives in TanStack Query) + Tailwind + lucide.

### Routes (`apps/web/src/App.tsx`)

| Path | Component / behavior |
| --- | --- |
| `/login` | `LoginPage` (redirects to `/app/dashboard` when signed in) |
| `/app` (layout) | `AppShell` |
| `/app` index | redirect `/app/dashboard` |
| `/app/dashboard` | `DashboardPage` |
| `/app/inbox` | `InboxPage` |
| `/app/appointments` | `AppointmentsPage` |
| `/app/doctors` | `DoctorSchedulesPage` |
| `/app/ai-receptionist` | `AiReceptionistPage` |
| `/app/ai-simulator` | redirect → `/app/ai-receptionist` |
| `/app/companies` | redirect → `/app/dashboard` (**unsupported**) |
| `/app/people` | `PeoplePage` |
| `/app/follow-ups` | `FollowUpsPage` |
| `/app/leads` | redirect → `/app/follow-ups` |
| `/app/deals` | redirect → `/app/dashboard` (**unsupported**) |
| `/app/opportunities` | redirect → `/app/dashboard` |
| `/app/tasks` | `TasksPage` |
| `/app/notes` | `NotesPage` |
| `/app/team` | `TeamPage` |
| `/app/workflows` | redirect → `/app/dashboard` (**unsupported**) |
| `/app/settings` | `SettingsPage` |
| `/inbox/pop` | `PopoutInboxPage` |
| `/confirm/:token` | `PublicConfirmPage` |
| `/appointment-confirmation/:token` | `PublicConfirmPage` |
| `*` | redirect (session-aware) |

Dead/unrouted page components: `CompaniesPage.tsx`, `DealsPage.tsx`,
`LeadsPage.tsx`, `WorkflowsPage.tsx`, `EmployeeDashboard.tsx` (present,
not routed — candidate cleanup).

### Shared components (`apps/web/src/components`)

`AddFieldMenu`, `AgentStatusControl`, `AiRunPanel`, `appointment-form`,
`CasePanel`, `ChannelCredentials`, `company-form`, `ConversationList`,
`ConversationSignals`, `ConversationThread`, `CreateRecordDrawer`,
`CustomerCard`, `CxProfilePanel`, `deal-form`, `DonutMetric`,
`EmployeeAdmin`, `FloatingInbox`, `FloatingWindow`, `follow-up-form`,
`InboxWorkspace`, `lead-form`, `ListView`, `notes-form`, `NotesSection`,
`NotificationBell`, `PatientProfileDrawer`, `ReturningCustomerBanner`,
`SatisfactionDonut`, `task-form`, `TicketHistoryPanel`, `ViewSwitcher`,
`WhatsAppReminderModal`. Design-system primitives in
`packages/ui/src/components`: `Button`, `Field`, `Panel`, `Sparkline`,
`Spinner`, `StatusChip`; `direction.tsx` RTL provider.

### Data hooks (`apps/web/src/lib`)

TanStack Query hooks: `session.ts` (`useSession`, `useLogin`,
`useLogout`), `api.ts` (fetch wrapper, `ApiError`), `accounts.ts`,
`customers.ts`, `clinic-ai.ts`, `inbox.ts`, `notes.ts`, `reminders.ts`,
`views.tsx`, `workflows.ts`, `locale.ts` (`useLocale`), `theme.ts`,
`ui.ts`, `navigate-cx.ts`, `useResizableColumns.ts`, `format.ts`.

### i18n

Single flat dictionary `STRINGS` in `apps/web/src/lib/i18n.ts`
(≈800 lines, `ar`/`en` per key), flat keys namespaced by prefix:
`app.`, `login.`, `nav.`, `projects.`, `dashboard.`, `inbox.`, `filter.`,
`thread.`, `author.`, `status.`, `mode.`, `priority.`, `customer.`,
`customers.`, `channels.`, `simulate.`, `knowledge.`, `doc.status.`,
`search.`, `agent.`, `runs.`, `decision.`, `signals.`, `rules.`,
`signal.`, `workflows.`, `team.`, `availability.`, `import.`, `creds.`,
`analytics.`, `notifications.`, `system.`, `audit.`, `common.`,
`case.`, `outcome.`, `admin.`, `leads.`, `followUps.`, `appointments.`,
`deals.`, `companies.`, `people.`, `notes.`, `tasks.`, `list.`.
Target: Transloco or equivalent runtime i18n with per-feature
namespaces; owner = Angular agent (Agent K) + docs (Agent M).

Known i18n debt: hard-coded English in `ListView.tsx`
("Filter", "Sort", "Move left", "Hide") and assorted tooltips.

---

## 4. Queues / jobs / retries / workers

`QueueName` (`packages/messaging/src/queues.ts`):
`incoming-message`, `ai-response`, `scoring`, `summary`,
`document-processing`, `outbound-send`, `escalation-routing`,
`analytics-rollup`. Producer=API (`MessageQueueService`,
`KnowledgeQueueService`), consumer=`apps/worker`.

Retry policy (`DEFAULT_JOB_OPTIONS`): `attempts: 5`, exponential backoff
`delay: 2000ms`, `removeOnComplete { age: 3600, count: 1000 }`,
`removeOnFail { age: 7*24*3600 }`. `QUEUE_DRIVER === 'inline'` runs
inline (dev without Redis); otherwise BullMQ on `REDIS_URL`.
Worker concurrency default 5; worker logs duplicate inbound as info, not
error. Idempotency: `messages` unique `(projectId, channel,
externalMessageId)`; inbound pipeline returns `{status:'duplicate'}`;
outbound job carries provider `externalMessageId`; no explicit job-id
key — dedupe guarantee is the DB constraint. No DLQ/replay service yet
(**gap for Agent E**).

---

## 5. Connectors / webhooks / secrets / capabilities

`CHANNEL_CAPABILITIES` (`packages/connectors/src/capabilities.ts`):
MOCK (text 4000, no signature verify, sessionWindowHours null),
WHATSAPP (text 4096, 24h session window, template required outside,
Meta signature), INSTAGRAM (text 1000, 24h window, Meta signature),
MESSENGER (text 2000, 24h window, Meta signature), TELEGRAM (text 4096,
no window, Telegram secret-token header).

Secrets: channel `credentials` encrypted at rest with AES-256-GCM
(`CredentialBox` in `packages/auth/src/secret-box.ts`, key =
`ENCRYPTION_KEY`), write-only via `PUT .../credentials`; never selected
into API responses. Webhook routes listed in §1. Provider event IDs:
WhatsApp `messages[].id`, Messenger `event.message.mid`,
Instagram via Messenger Platform, Telegram
`${message.chat.id}:${message.message_id}` — all normalized to
`externalMessageId` as the idempotency key. `verifyWebhook` per
connector (Meta HMAC-SHA256 over raw bytes in
`meta/signature.ts`, Telegram constant-time header compare).
`ConnectorNotConfiguredError` (`CONNECTOR_NOT_CONFIGURED`) propagates
rather than pretending success.

AI providers: `packages/ai/src/providers/{mock,openai,anthropic,failover}.ts`
+ embeddings `mock`/`openai` (`embeddings/factory.ts`); selectable via
`AIProviderKind`; failover chain stored per `AIAgentVersion`.

---

## 6. Dashboard metrics (current definitions)

From `apps/api/src/analytics/analytics.service.ts` (last 30 days unless
noted):

- `conversations`: total conversations in scope.
- `aiResolutionRate`: % of resolved cases/conversations handled without
  escalation to a human.
- `escalationRate`: % of conversations that produced an `Escalation`.
- `medianFirstResponseMinutes`: median minutes conversation → first
  outbound reply (AI or human).
- `medianResolutionMinutes`: median minutes conversation → RESOLVED.
- `csat` / `dsat`: % of rated `CaseResolution` rows with
  `customerSatisfied` true / false (null when no ratings — "no data"
  vs zero).
- `rated`: count of resolutions with a satisfaction answer.
- `resolveRate`: % of resolutions with outcome RESOLVED.
- `casesResolved`: count of resolutions outcome RESOLVED.
- `averageHandlingMinutes`: active working minutes from
  `ConversationEvent` ASSIGNED→RESOLVED (not wall-clock).
- `medianLatencyMs`: median AI run latency.
- `aiRuns`, `aiCost` (sum of `AIUsage.estimatedCost` USD), `aiLatency`,
  `byChannel`, `gaps` (`KnowledgeGap`), `workload` (employee
  `activeConversationCount` vs `maxConcurrentConversations`), unassigned
  escalations.
- Clinic executive analytics (`clinic.service.getExecutiveAnalytics`):
  AI booking conversion, WhatsApp no-show rate, procedure revenue,
  clinician utilization, booking funnel counts, weekly trends — backed by
  `appointments`/`procedures`/`doctors` aggregates; definitions live in
  the service, not a metric catalogue (**gap for Agent H**).

---

## 7. Tests (unit / integration / browser)

Vitest, root `vitest.config.ts`, single shared-DB integration files run
serially. Browser-level (Playwright): **none present** (**gap**).

Unit:
`packages/auth/src/policy.test.ts`, `session-token.test.ts`,
`secret-box.test.ts`, `packages/database/src/tenant.test.ts`,
`apps/api/test/inbox-filter.test.ts`,
`packages/connectors/src/mock.connector.test.ts`,
`meta/signature.test.ts`, `real-connectors.test.ts` (pure, no DB),
`packages/knowledge-base/src/chunking.test.ts`, `parsers.test.ts`,
`packages/ai/src/embeddings/mock.provider.test.ts`,
`orchestrator/prompt.test.ts`, `scoring/evaluator.test.ts`,
`scoring/rules.test.ts`, `tools/clinic-scheduling.test.ts`,
`tools/executor.test.ts`, `packages/routing/src/strategy.test.ts`,
`working-hours.test.ts`, `import.test.ts`.

Integration (require `DATABASE_URL`; skip without it — recorded as
FAIL evidence):
`apps/api/test/acceptance.integration.test.ts`,
`case-classification.integration.test.ts`,
`inbound-pipeline.integration.test.ts`,
`knowledge-retrieval.integration.test.ts`,
`tenant-isolation.integration.test.ts`.

Counts (2026-10-04 baseline claim): 238 passing, 41 skipped.

---

## 8. Documented-but-not-implemented

From `PROJECT_REQUIREMENTS.md` §2 baseline + `PR.md` scope:

- Entire target stack: Spring Boot/Java 21, Spring Security, Spring AI,
  Flyway, Angular SPA, Gradle build — not present.
- Admin control plane as a unified surface (users/policies/jobs/DB state/
  connectors/AI policy/exports/deletion/audit health) — not implemented;
  admin capability is scattered across existing endpoints/pages.
- Finance dashboard / immutable `UsageEvent` ledger / rate cards /
  period locks / reconciliation — not implemented.
- Generic export service, asynchronous `ExportJob`s, DOCX export — not
  implemented (inline Excel download in appointments UI only).
- Versioned `ReportDefinition`s — not implemented.
- Transactional outbox table + dispatcher with leases/DLQ/replay —
  not implemented (queues only).
- OIDC/OAuth2 resource-server path — not implemented.
- PostgreSQL RLS — not implemented.
- `AdminAuditEvent`, `SecurityEvent`, `JobIntegrationEvent`,
  `UsageEvent` separate audit streams — not implemented (single
  `audit_logs` table today).
- Teams/departments/SLA policy objects, holiday calendars, collision
  records — not implemented (working-hours exist; SLA clocks as such do
  not, only first/resolution response medians in analytics).
- Workflow definitions/versions/dry-run — not implemented (routing +
  escalation rules exist and are deterministic).
- AI policy/budget/approval/redaction records — not implemented
  (prompt-injection defense exists as heuristic flags in scoring).
- Playwright/browser tests, performance load tests, backup/restore
  scripts, observability (OTel) — not implemented.
- `AIRunDecision.RESOLVE` value exists in schema but not in migration
  enum (drift, see §2).
- `Permission.PROJECT_ARCHIVE` declared but unused; conversation
  TAKE_OVER/RETURN_TO_AI permissions declared but no dedicated route.

---

## 9. Known blockers / hard-coded colors / dead code / lint+type blockers

- Typecheck: 3 implicit-`any` in `apps/api/src/main.ts` HTTPS redirect
  middleware (lines 23–29): `(req, res, next)` untyped.
- Lint: 118 errors / 39 warnings — unused imports/vars, untyped params,
  `clinic.controller.ts` heavy `any` bodies, CommonJS scripts.
- Integration tests skip without `DATABASE_URL`; Docker absent in
  sandbox; npm scripts wire vitest only.
- Prisma schema ↔ migration drift: `EmployeeAvailabilityStatus` enum,
  `AIRunDecision.RESOLVE`, possibly V1.2 tables (`Lead` has no
  `@@map`; several V1.2 columns/tables may be drift from `db push`).
- Hard-coded colors (violations of `docs/migration-AGENT.md` token rule):
  dominant pattern `#0f5b4c` across `AiReceptionistPage`,
  `AppointmentsPage`, `PublicConfirmPage`, `PatientProfileDrawer`,
  `PeoplePage`, `appointment-form`, `#00a884`, `#25D366`, `#128C7E`,
  `#0E6E63`, `#1a1d23`/`#c8cdd6` dark dropdowns in `AddFieldMenu` and
  `ListView`, gradients `bg-gradient-to-br`/`bg-gradient-to-r` in
  `AiReceptionistPage`, `AppointmentsPage`, `PublicConfirmPage`,
  `PatientProfileDrawer` — clinical spec forbids gradients and inline
  hex; migrate to tokens in the Angular shell.
- Dead code: unrouted page components (`CompaniesPage`, `DealsPage`,
  `LeadsPage`, `WorkflowsPage`, `EmployeeDashboard`);
  `mock-webhook.controller` and inline `QUEUE_DRIVER` path are dev
  adapters (retain); `Deal.customer` field is a raw string, not an FK
  (inconsistent with other ownership fields).
- Unsupported routes in UI: `/app/companies`, `/app/deals`,
  `/app/opportunities`, `/app/leads`, `/app/workflows` all redirect to
  dashboard — CRM middle workspace pages exist but are not wired into
  the nav.
- Security gaps noted for Agent C (not fixed here): `ClinicController`
  and `AppointmentsController` use `AuthGuard` without
  `@RequirePermissions`; `clinic/reminders/respond` takes
  `appointmentId` in the body without a tenant check; public
  `/public/appointments/:token` has no rate limit; `AIRunToolCall`
  omits arguments/results from the trace API (deliberate, documented).
- No CSRF token on cookie-authenticated mutations (SameSite cookie +
  throttler today); add CSRF in Spring Security phase.
- Realtime is single-process SSE (`RealtimeGateway`); horizontal
  scaling needs Redis pub/sub (documented in code comment).

---

## 10. Legacy → target domain vocabulary

| Legacy (today) | Target (Spring modules) |
| --- | --- |
| Organization | Tenant (`TenantContext.organizationId`) |
| Project | Project (`TenantContext.projectIds`) |
| User + Employee + ProjectMembership | Identity & Access principals (`ADMIN`/`EMPLOYEE`) |
| Customer / CustomerIdentity | CRM core customer / contact identities |
| Conversation / Message / ConversationEvent | Inbox/helpdesk ticket / article / domain history |
| ChannelConnection + ChannelType + credentials | Channels connector + ConnectorCredentials (encrypted) |
| EscalationRule / Escalation / RoutingRule | Workflows-SLA-Routing rules/instances |
| AIAgent / AIAgentVersion / AIRun / AIUsage | Knowledge-AI agent config / run / provenance / usage record |
| KnowledgeBase / Document / DocumentChunk / KnowledgeGap | Knowledge-AI ingestion / retrieval index / gap backlog |
| ConversationScore | Workflow scoring history |
| AgentStatusSession | Agent presence session (workflows) |
| Task / Lead / Deal / Account / Note / View | CRM middle-workspace entities |
| CaseClassificationQuestion / CaseResolution | Inbox case classification / resolution |
| Doctor / Procedure / Appointment | Clinic domain module |
| `audit_logs` | Split into `AdminAuditEvent`, `SecurityEvent`, `JobIntegrationEvent` (target) |
| `sessions` | Spring Security session (target: same cookie contract) |
| BullMQ queue + DEFAULT_JOB_OPTIONS | Outbox + worker runtime (target) |
| `estimatedCost` on AIUsage | Seed row for immutable `UsageEvent` ledger (target) |

Every row maps to a target module, compatibility adapter, or explicit
deferral; §8 lists the explicit deferrals.

---

## 11. OpenAPI compatibility matrix (skeleton)

Contract target: `/api/v1`, Spring OpenAPI. Legacy base is `/api`.
Compatibility rule: new endpoints under `/api/v1`; legacy `/api` kept
read-mostly during cutover; snake_case namespaces may diverge behind a
compatibility adapter.

| Legacy path | Legacy method | Legacy permission | Target module | Target path (skeleton) | Compat action |
| --- | --- | --- | --- | --- | --- |
| `/api/auth/login` | POST | public | identity-access | `/api/v1/auth/login` | preserve contract |
| `/api/auth/logout` | POST | session | identity-access | `/api/v1/auth/logout` | preserve |
| `/api/auth/me` | GET | session | identity-access | `/api/v1/auth/me` | preserve |
| `/api/organization` | GET/PATCH | ORGANIZATION_* | administration | `/api/v1/tenants/current` | adapter |
| `/api/projects` (+/:id) | CRUD | PROJECT_* | administration | `/api/v1/projects` | preserve |
| `/api/employees` (+/:id) | CRUD | EMPLOYEE_* | identity-access/admin | `/api/v1/users`+`/api/v1/memberships` | split, adapter |
| `/api/employees/routing`, `/me/availability`, `/me/status`, `/employees/:id/working-hours`, `/employees/:id/skills` | GET/PUT/PATCH | EMPLOYEE_* | workflows-sla-routing | `/api/v1/routing/employees` | adapter |
| `/api/employees/import[/preview]` | POST | EMPLOYEE_IMPORT | workflows-sla-routing | `/api/v1/routing/employees/imports` | adapter |
| `/api/projects/:projectId/routing-rules` | CRUD | ROUTING_RULE_MANAGE | workflows-sla-routing | `/api/v1/workflows/routing-rules` | version-bump |
| `/api/customers` (+/:id, /cx/:cxId) | GET/PATCH | CUSTOMER_* | crm-core | `/api/v1/customers` | preserve |
| `/api/conversations` (+/:id, /messages, /assignment, /close) | GET/POST/PATCH | CONVERSATION_* | inbox-helpdesk | `/api/v1/conversations` | preserve + version checks |
| `/api/accounts`, `/notes`, `/views`, `/leads`, `/follow-ups`, `/deals`, `/tasks` | CRUD | per-entity | crm-core | `/api/v1/accounts`…`/api/v1/tasks` | preserve |
| `/api/projects/:projectId/classification-questions`, `/conversations/:id/classification|resolution|close|reopen` | GET/PUT/POST | CONVERSATION_*, ESCALATION_RULE_MANAGE | inbox-helpdesk | `/api/v1/cases/...` | adapter |
| `/api/projects/:projectId/channels` (+/credentials) | GET/POST/PUT/PATCH | CHANNEL_* | channels | `/api/v1/channels` | preserve |
| `/api/webhooks/meta/:id`, `/api/webhooks/telegram/:id` | GET/POST | public+signature | channels | `/api/v1/webhooks/...` | preserve raw-body verify |
| `/api/dev/mock/incoming-message` | POST | CONVERSATION_REPLY | channels (dev) | `/api/v1/dev/mock/...` | preserve, dev-only |
| `/api/projects/:projectId/knowledge/...` | GET/POST/DELETE | KNOWLEDGE_* | knowledge-ai | `/api/v1/knowledge/...` | preserve |
| `/api/projects/:projectId/agent`, `/agent/versions*` | GET/PUT/POST | AI_AGENT_* | knowledge-ai | `/api/v1/ai/agents` | preserve versioning |
| `/api/ai-runs`, `/ai-runs/:id` | GET | AI_RUN_READ | knowledge-ai | `/api/v1/ai/runs` | preserve, no CoT |
| `/api/projects/:projectId/escalation-rules` | CRUD | ESCALATION_RULE_* | workflows-sla-routing | `/api/v1/workflows/escalation-rules` | version-bump |
| `/api/conversations/:id/scores`, `/escalations` | GET | CONVERSATION_READ | workflows-sla-routing | `/api/v1/conversations/:id/scores` | preserve |
| `/api/notifications*`, `/api/realtime/stream` | GET/POST/SSE | PROJECT_READ | notifications-realtime | `/api/v1/notifications`, `/api/v1/realtime/stream` | SSE preserved or WS behind ADR |
| `/api/me/analytics`, `/analytics/summary`, `/projects/:id/analytics`, `/employees/:id/analytics` | GET | ANALYTICS_* | reporting-finance | `/api/v1/analytics/...` | preserve definitions |
| `/api/audit-logs` | GET | AUDIT_LOG_READ | administration | `/api/v1/admin/audit` | unify streams |
| `/api/escalations/unassigned` | GET | CONVERSATION_READ | workflows-sla-routing | `/api/v1/escalations?unassigned=true` | preserve |
| `/api/health` | GET | public | operations-observability | `/actuator/health`, `/api/v1/health` | preserve bare probe |
| `/api/system/health` | GET | SYSTEM_HEALTH_READ | operations-observability | `/api/v1/admin/health` | enrich |
| `/api/appointments` (+/:id) | CRUD | AuthGuard only → EMPLOYEE clinical role | clinic | `/api/v1/clinic/appointments` | add permission decorators |
| `/api/clinic/*` | CRUD+RPC | none today → per-route permissions | clinic | `/api/v1/clinic/...` | add policy |
| `/api/public/appointments/:token` | GET/POST | public | clinic | `/api/v1/public/appointments/:token` | rate-limit |
