# Dental Clinic — Requirements Traceability (verified)

**Status:** Populated. Every row below was checked against the code and the live schema on
2026-10-12 at commit `f182ada`, not read from the requirements document.

**Source:** `source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx`, via
`firstelement-dental-clinic-feature-kit/DENTAL_CLINIC_REQUIREMENTS.md`.

**Verdict vocabulary**

| | |
|---|---|
| **EXISTS** | Present and behaves as described |
| **PARTIAL** | Present but materially incomplete |
| **MISSING** | Not present |
| **DIFFERS** | Present but behaves differently from the requirement |
| **RULED OUT** | Deliberately excluded by an existing ADR |

> The register that this replaces stated its statuses were "inferred from the architecture overview,
> not from the code". That inference was wrong on several rows in both directions — some features it
> called missing exist, and some it called partial are far more broken than that. Those corrections
> are recorded here rather than silently corrected.

---

## 1. Patients

| ID | Feature | Register said | **Verified** | Evidence | Blocked by |
|---|---|---|---|---|---|
| P-1 | Patient file, search by name/phone/file id | Likely exists | **PARTIAL** — entity is `customers`, and the clinic projection drops `customerId`, so there is no path from a clinic row to a patient file | `CustomerRepository.java:167`; `endpoints.ts:1228` | DEC-015 |
| P-2 | Arabic-aware name normalisation, E.164 phones | Missing | **MISSING** — phone pattern is `^\+?[0-9\s-]{6,20}$`, not E.164; no `Normalizer` anywhere. `أحمد` ≠ `احمد` | `UpdateCustomerRequest.java:49`; `CustomerRepository.java:171` | — |
| P-3 | Duplicate warning before create | Partial | **PARTIAL** — exists only on `POST /customers/resolve-identity`, exact match after `lower()`, no normalisation; never auto-merges | `IdentityResolver.java:232`, `:48` | P-2 |
| P-4 | Patient timeline | Likely exists | **PARTIAL** — three separate lists, deliberately not merged (FR-3.4) | `CustomerRepository.java:236,270,292` | — |
| P-5 | Messaging consent and preferences | Missing | **MISSING** — no consent column or table | schema scan | DEC-007 |
| P-6 | Create/update audit trail | Partial | **PARTIAL, worse** — update is audited; **create writes no audit row at all** | `CustomerService.java:261`; `TimelineEventMapper.java:114` | — |
| P-7 | Medical alerts | Decision | **MISSING** — nowhere to store an alert | schema scan | DEC-007 |
| P-8 | Patient attachments | Decision | **MISSING** — `documents` requires a knowledge base; no customer FK | `baseline:948` | DEC-007 |
| P-9 | Bulk import | Missing | **MISSING** — only importer is `RosterImportService` (employees) | — | DEC-008 |

## 2. Doctors, services, schedules

| ID | Feature | Register said | **Verified** | Evidence | Blocked by |
|---|---|---|---|---|---|
| D-1 | Working hours, leave, unavailable | Likely exists | **PARTIAL** — working hours exist and are read-only; **no leave or unavailable period anywhere** | `baseline:906`; `ClinicController.java:38` | DEC-001 |
| D-2 | Service catalog AR/EN, duration, price, doctors | Partial | **PARTIAL** — duration and price exist; **no AR/EN pair**, **no doctor↔service link** | `baseline:1177`; `ClinicRepository.java:74` | — |
| D-3 | Buffer between appointments | Missing | **DIFFERS — the register is wrong.** `procedures.bufferMinutes` exists, is projected and is typed in the client. It is **dead data**: nothing reads it | `baseline:1184`; `ClinicModels.java:121` | DEC-001 |
| D-4 | Chairs / rooms as resources | Decision | **MISSING** — no concept at all | schema scan | DEC-002 |
| D-5 | Clinic holidays and opening hours | Partial | **PARTIAL** — per-doctor hours only; no clinic entity, no holidays. `sla_calendars` is SLA business hours and is not read by clinic code | `baseline:915`; `SlaRepository.java:23` | DEC-001 |
| D-6 | Multi-session procedures | Missing | **MISSING** — `procedures` is a flat row | `baseline:1183` | DEC-001 |

## 3. Appointments

**Root cause of this whole section: `ClinicController` has seven `@GetMapping` handlers and zero write
endpoints. There is no `insert into appointments` or `update appointments` in production code.** The
legacy NestJS controller has 22 non-public handlers plus 2 on a separate public appointment controller (24 total), including booking, reschedule, cancel, slots, availability
and reminders — this section is a **port**, not a build.

| ID | Feature | Register said | **Verified** | Evidence | Blocked by |
|---|---|---|---|---|---|
| A-1 | Day/week/month calendar, filter by doctor | Likely exists | **DIFFERS** — a 50-row keyset list over a ≤366-day window. No calendar grid, no doctor filter in controller, SQL, contract or UI | `ClinicController.java:175`; `ClinicRepository.java:115` | — |
| A-2 | Create/edit/cancel/reschedule with reason and actor | Likely exists | **MISSING** — no write route; no `appointment:create/reschedule/cancel` permission exists by design | `Permission.java:132`; `RolePermissions.java:96` | DEC-003 |
| A-3 | DB exclusion constraint on doctor + time | Verify | **MISSING** — **zero exclusion constraints exist database-wide**, and no triggers on `appointments` | `pg_constraint` query | DEC-001, DEC-002 |
| A-4 | One availability service for UI and AI | Partial | **MISSING** — no availability class, route or service; the AI tool deliberately reads none | `AppointmentProposalTool.java:24` | DEC-001 |
| A-5 | Status lifecycle | Partial | **DIFFERS** — enum is `PENDING, APPROVED, COMPLETED, CANCELLED, NO_SHOW`, used only as a filter whitelist. No arrived / in-treatment / booked. **`status` is unconstrained text with `DEFAULT 'PENDING'`, and the table has zero rows.** Frontend labels disagree with the backend enum | `ClinicModels.java:175`; `baseline:624`; `people.strings.ts:107` | DEC-003 |
| A-6 | Booking source recorded | Partial | **DIFFERS** — one boolean `isAiProposed DEFAULT true`, not a taxonomy; the default makes unmigrated rows look AI-proposed | `baseline:627` | — |
| A-7 | Cancellation policy, notice, approval cutoff | Missing | **MISSING** | schema scan | DEC-003 |
| A-8 | Walk-in / emergency slots | Missing | **MISSING** — the only walk-in is a test fixture string | `ClinicFixture.java:199` | DEC-003 |
| A-9 | No-show handling | Missing | **MISSING** — `NO_SHOW` is a readable label nothing sets | `ClinicModels.java:177` | DEC-003 |
| A-10 | Waitlist | Missing | **MISSING** — no table. A `tasks` table exists in the baseline and is entirely unclaimed | schema scan | — |
| A-11 | Recall rules | Missing | **MISSING** | — | DEC-001 |

## 4. Reminders and confirmations

**There is no scheduler in the codebase.** `@Scheduled`, `@Cron`, `Quartz` return zero production
hits. The outbox has retry backoff but no "publish at T" primitive.

| ID | Feature | Register said | **Verified** | Evidence | Blocked by |
|---|---|---|---|---|---|
| R-1 | Confirmation after save, never before | Missing | **MISSING** — nothing to hang it on; and `NotificationType` has no delivery-failure member | `ClinicController.java:26` | DEC-005 |
| R-2 | Reminder schedule, quiet hours, dedupe | Missing | **MISSING** — no reminder queue, no table, no `REMINDER_*` permission, no due-time mechanism | `QueueName.java:26`; `OutboxRepository.java:44` | DEC-005 |
| R-3 | Patient replies become tasks or proposals | Missing | **MISSING** — the `tasks` table is unclaimed; the proposal tool throws unconditionally | `AppointmentProposalTool.java:101` | DEC-005 |
| R-4 | Delivery status sent/delivered/failed | Partial | **PARTIAL, worse** — `PENDING → SENT \| FAILED` is the only reachable path. **`DELIVERED`/`READ` are dead code** whose only caller is a test, and Meta `statuses` payloads are verified then discarded. The UI renders labels for states the runtime cannot produce | `OutboundDeliveryRepository.java:101`; `MetaWebhookParser.java:59` | DEC-005 |
| R-5 | Opt-out and consent check before every send | Missing | **MISSING** — the outbound pre-flight has three checks and **none is consent**. "stop" is scored as a cancellation intent | `OutboundGuard.java:40`; `ConversationScoring.java:89` | DEC-005 |
| R-6 | Approved templates per language | Missing | **MISSING** — connector is `sendText` only; no template name, language or parameters on the wire | `MessagingConnector.java:52`; `MetaConnector.java:119` | DEC-005 |

## 5. Channels

| ID | Feature | Register said | **Verified** | Evidence | Blocked by |
|---|---|---|---|---|---|
| C-1 | WhatsApp production setup | Partial | **PARTIAL, thinner** — signature verification and handshake are done; number management, business verification and template approval are absent, and **there is no API to create or edit a connection** | `ChannelCapabilityController.java:54` | DEC-005 |
| C-2 | Idempotent webhook handling | Verify | **EXISTS** — three layers: provider-event reservation, outbox key, and a unique index on `(projectId, channel, externalMessageId)`. Retried delivery cannot duplicate. (The "or bookings" half is vacuous — there is no booking write path) | `ProviderEventStore.java:75`; `baseline:2279` | — |
| C-3 | WhatsApp 24-hour window | Missing | **DIFFERS — the register is wrong. This is implemented**: declared, computed from real inbound timestamps, enforced fail-closed on every send, refusal reason persisted. The *second* clause is missing — outside the window sending is impossible, not "restricted to approved templates" | `ConnectorCatalog.java:34`; `OutboundDeliveryService.java:191` | DEC-005 |
| C-4 | Voice-note policy | Decision | **MISSING** — no transcription and no ask-for-text reply. An audio-only message reaches the human as the literal string `[audio]` | `MetaWebhookParser.java:130` | DEC-005 |
| C-5 | Extra channels | Decision | **PARTIAL — the register is wrong on a fact: Messenger also exists.** Five channels ship: MOCK, WHATSAPP, INSTAGRAM, MESSENGER, TELEGRAM. No SMS, email or voice | `ChannelType.java:10`; `baseline:176` | DEC-005 |

## 6. AI assistant

**Every row marked Partial should be read as "infrastructure present, product capability absent".**

| ID | Feature | Register said | **Verified** | Evidence | Blocked by |
|---|---|---|---|---|---|
| AI-1 | Real LLM adapter with failover | Partial | **PARTIAL** — failover, budgets, timeouts and redaction are real and tested; **there is no LLM adapter**. `OPENAI`/`ANTHROPIC` are enum constants with zero implementations | `MockAiChatProvider.java:51`; `AiProviderKind.java:22` | DEC-006 |
| AI-2 | Real multilingual embeddings | Partial | **PARTIAL** — a character-trigram hash the class itself calls "explicitly **not** semantic". No recall test, no re-embedding plan | `DeterministicEmbeddingProvider.java:37` | DEC-006 |
| AI-3 | Structured facts + approved free text | Partial | **DIFFERS — the register is wrong.** There is **no `approved` flag, no version column and no owner**; ingestion goes straight to `READY`. **No structured-facts tool exists** — `procedures` holds name, duration and price and the AI never reads them | `baseline:948`; `KnowledgeIngestionService.java:212`; `AiToolCatalog.java:74` | DEC-006 |
| AI-4 | Per-project system prompt | Missing | **PARTIAL, differently** — the column exists, is project-scoped and **is read in production**. Nothing ever writes it outside tests | `baseline:478`; `AiPolicyRepository.java:197` | DEC-006 |
| AI-5 | Appointment tools | Missing | **MISSING** — two tools exist (`conversation.lookup`, `appointment.propose`); neither books. The legacy `packages/ai` defined ten that worked | `AiToolCatalog.java:74`; `packages/ai/src/tools/definitions.ts` | DEC-006 |
| AI-6 | Confirm only from a successful tool result | Missing | **MISSING, worse** — generation happens once and **tool results never re-enter the model**; the shipped draft is the pre-tool text. Nothing stops the model asserting an unsaved booking | `AiOrchestrator.java:316,356` | DEC-006 |
| AI-7 | Identity check before revealing an appointment | Missing | **MISSING** — no verification of any kind, and the one consequential tool takes **no patient identifier at all** | `AppointmentProposalTool.java:77` | DEC-006 |
| AI-8 | Urgent-symptom handoff | Missing | **MISSING** — the AI assigns no priority, writes no escalation row and starts no clock. The urgency engine exists and is **never called from the AI path** | `AiResponseJobHandler.java:236`; `ConversationScoring.java:57` | DEC-006 |
| AI-9 | Handoff with summary, assignment, SLA clock | Partial | **PARTIAL** — summary and attribution exist; **no assignment, no SLA clock, no inbox row**. And **no route exists to approve a pending approval**, so an escalation is a dead end | `AdminReadModelController.java:384` | DEC-006 |
| AI-10 | Autonomy levels and kill switch | Missing | **MISSING** — no levels, no per-project control. The only switch is a deployment-global boolean; the only limit is a flat per-tenant counter | `AiRagProperties.java:38,103` | DEC-006 |
| AI-11 | Privacy: DPA, minimal prompt data, opt-in, retention | Missing | **MISSING** — no consent capture, no per-tenant opt-in, no DPA artifact. Redaction exists but is a different control | `AiResponseTrigger.java:98` | DEC-006, DEC-007 |
| AI-12 | Quality measures | Missing | **MISSING** — cost per conversation exists and is queryable; the four quality measures are absent, and **the data to compute them does not exist** — nothing links a draft to the human reply | `FinanceMetricCatalogue.java:115` | DEC-006 |
| AI-13 | Safety test set | Missing | **MISSING** — no eval harness, no golden set, no CI gate. Injection detection is telemetry-only by design | `UntrustedContentAdvisor.java:66` | DEC-006 |

## 7. Payments

`reporting-finance` is **the platform's own usage ledger, not patient billing** — confirmed against
`V20261005090000:28-32`.

| ID | Feature | Register said | **Verified** | Evidence |
|---|---|---|---|---|
| F-1 | Patient payment ledger and balance | Missing | **RULED OUT** — ADR-0007 rule 7 forbids payment execution and external billing | `V20261005090000:28` |
| F-2 | Invoices, partial payment, receipts | Decision | **MISSING** — nothing started | — |
| F-3 | Discounts, refunds, adjustments | Missing | **PARTIAL / DIFFERENT SUBJECT** — a permission-gated audited adjustment machinery exists for **platform usage**, not patient money. Zero discounts, zero refunds | `FinanceController.java:431` |
| F-4 | Currencies, methods, tax, e-invoicing | Decision | **MISSING** — currency *validation* only; no methods, no tax engine | `usage_ledger.sql:169` |
| F-5 | Insurance and pre-approval | Decision | **MISSING** | — |
| F-6 | Planned vs invoiced vs collected | Parked | **PARKED — accurate.** The "no data ≠ zero" trap is already engineered against in the database | `usage_ledger.sql:823` |
| F-7 | AI usage pricing source | Decision | **DIFFERS** — `usage_events` stores **no cost** for AI by design and a guard *raises* if a rate-card version is added there; the version is pinned on the rollup | `usage_ledger.sql:432` |

## 8. Users, roles, permissions

| ID | Feature | Register said | **Verified** | Evidence |
|---|---|---|---|---|
| U-1 | Map document roles; bundles for dentist, assistant, accountant | Partial | **PARTIAL** — one of five roles maps (receptionist → `CLINIC_STAFF`). No dentist, assistant or accountant bundle. `UserRole`'s own comment describes a booking capability the codebase says does not exist | `UserRole.java:30`; `RolePermissions.java:129` |
| U-2 | Permission matrix as automated tests | Missing | **PARTIAL, understated** — substantial role-contract and route-permission tests exist; there is no matrix-driven suite, and no dentist/assistant/accountant rows to test | `UserRoleContractTest.java:62` |
| U-3 | Audit for permission changes, refunds, cancellations, merges | Partial | **PARTIAL — 1 of 4.** Only permission changes are audited. The other three are audit trails for actions that do not exist, and merges are **deliberately forbidden** | `MembershipAdminService.java:99`; `IdentityResolver.java:48` |
| U-4 | AI acts as its own scoped actor | Verify | **EXISTS — better than the row.** `PrincipalType.AI_AGENT` everywhere: authorization, audit and the finance ledger, with exactly 3 capabilities | `AiResponseJobHandler.java:301` |
| U-5 | Configurable roles; disable and re-enable accounts | Likely exists | **DIFFERS — the register is wrong.** Disable and role change are real and revoke sessions. **Re-enable does not exist.** Roles are a fixed 3-value enum, not configurable | `IdentityAdminController.java:63` |
| U-6 | Patient self-service portal | Parked | **PARKED — accurate** | — |

## 9. Dashboards and reports

| ID | Feature | Register said | **Verified** | Evidence |
|---|---|---|---|---|
| H-1 | Daily/upcoming/cancelled/no-show summary | Partial | **PARTIAL** — cancellations and no-shows are real counts; no "today"/"upcoming" rollup and no widget | `ClinicRepository.java:163` |
| H-2 | Operational KPIs with written definitions | Partial | **PARTIAL, and the register is backwards.** The **written definitions are thoroughly built** — every group publishes a derivation sentence, window, source table and drill-through path. The **KPIs are missing**: no attendance rate, no no-show rate, no utilisation, no bookings-by-source. Refusing utilisation is a *correct* decision, not an omission | `ClinicAnalytics.java:76,124`; `ClinicReadService.java:241` |
| H-3 | AI KPIs | Missing | **MISSING** — the only AI response time in the repo is in the frozen legacy React tree | `apps/web/.../DashboardPage.tsx:126` |
| H-4 | Financial summary by doctor/service | Missing | **MISSING, structurally** — the ledger keys on project + dimension over a completeness-tested 12-value catalogue with no DOCTOR/SERVICE/PROCEDURE | `FinanceMetricCatalogue.java:154` |
| H-5 | Filters by date, doctor, service, plus exports | Likely exists | **PARTIAL** — date and status filters exist; **doctor and service do not**, despite the analytics already returning those breakdowns. Exports are substantial and correctly authorised | `ClinicController.java:183`; `ExportDownloadService.java:328` |

## 10. Non-functional

| ID | Feature | Register said | **Verified** | Evidence |
|---|---|---|---|---|
| N-1 | Retention/deletion/export policy per country and type | Decision | **PARTIAL** — per-tenant and per-class retention with legal hold exists, and is genuinely strong. **No country dimension. The purge job is explicitly off**, so retention is configured and never enforced | `MaintenanceCommandIntegrationTest.java:682` |
| N-2 | Data residency and hosting | Decision | **PARTIAL** — a real AI residency gate exists and is tested; the deployment region in the repo is **`ams`, not GCC**, and no legal confirmation is recorded | `deploy/fly/backend.fly.toml:2` |
| N-3 | Backup and restore tested | Likely exists | **EXISTS, understated.** It has **passed three times** with schema fingerprint and row-count comparison. But it ran against local dev seed data, and the evidence is under gitignored `build/` | `restore-checks.json` |
| N-4 | Arabic/English, RTL, formats, Hijri | Partial | **PARTIAL** — RTL, locale-aware formatting and bilingual strings are all real and tested; **Hijri is absent entirely** | `i18n.spec.ts:130` |
| N-5 | Clear failures; never claim success | Verify | **EXISTS — one of the strongest rows in the codebase.** Failure honesty is deliberate and commented; non-disclosing 404s are byte-identical for foreign and nonexistent ids | `OutboundDeliveryService.java:165`; `ClinicController.java:262` |
| N-6 | Tenant isolation for every table; consider RLS | Partial | **PARTIAL** — the application-layer half is arguably the strongest property in the repo, with composite tenant FKs and negative fixtures. **RLS is entirely absent** — zero `CREATE POLICY`, zero `ENABLE ROW LEVEL SECURITY` | `usage_ledger.sql:456`; `V20261009110000` |

---

## 11. Acceptance scenarios

| # | Scenario | Can it pass today? |
|---|---|---|
| 1 | Book an available slot; confirmation after save | **No** — no write path |
| 2 | Conflicting slot rejected, no confirmation | **No** — no write path, no exclusion constraint |
| 3 | Two simultaneous bookings, exactly one succeeds | **No** — no write path; double-booking is currently impossible only because booking is |
| 4 | Slot outside hours or during leave never offered | **No** — no availability engine, no leave table |
| 5 | Confirmation send fails; appointment stays, reception alerted | **No** — failure honesty is real; the appointment half does not exist |
| 6 | AI booking records source as AI, confirms after success | **No** — the tool can only propose |
| 7 | Duplicate warning, Arabic variants matched | **Partial** — the warning exists; Arabic matching does not |
| 8 | Unauthorized user opens protected data → denied | **Yes** — strongest row; byte-identical 404s |
| 9 | Cancellation records status and reason, frees the slot | **No** — no cancel path |
| 10 | Handoff with context summary and SLA clock | **Yes, for conversations** — not for appointments |
| 11 | Opted-out patient receives nothing | **No** — no consent layer exists |
| 12 | Outside the 24h window, only approved templates | **Partial** — the window guard is real; templates cannot be sent at all |

**1 of 12 passes. 2 pass in a different domain or partially. 9 fail.** Every failure traces to the same
root cause: there is no appointment write path.
---

## 12. Platform console � Stage 1b (added 2026-10-13)

Rows for the cross-tenant operator surface. Evidence is the test that fails if the property stops
holding, not the code that appears to implement it.

| # | Property | Status | Evidence |
|---|---|---|---|
| P-1 | A customer session cannot authenticate a platform route | **PROVEN** | PlatformConsoleBoundaryHttpIntegrationTest.customerSessionCannotReachAPlatformRoute � real login, real cookie, asserts 401 |
| P-2 | A platform session cannot authenticate a customer route | **PROVEN** | �platformSessionCannotReachACustomerRoute � asserts 401 on /api/v1/auth/me |
| P-3 | One cookie name cannot serve both tiers | **PROVEN** | �thePlatformCookieIsNotTheCustomerCookie � platform token replayed as e_session is 401 |
| P-4 | The filter never reads the customer cookie | **PROVEN by interaction** | PlatformOperatorAuthenticationFilterTest.customerSessionCookieIsIgnored � asserts indSessionByTokenHash is **never** called, not merely that it returns empty |
| P-5 | Platform filter runs only under the console prefix | **PROVEN** | PlatformOperatorAuthenticationFilterTest.shouldNotFilterOutsideTheConsole |
| P-6 | The operator is cleared even when downstream throws | **PROVEN** | �contextIsClearedAfterTheRequest � no pooled-thread leak of authority |
| P-7 | Password alone grants no authority | **PROVEN** | PlatformOperatorHandshakeIntegrationTest.passwordAloneIsNotAuthority |
| P-8 | A pending session cannot be elevated | **PROVEN** | �pendingSessionCannotBeElevated |
| P-9 | A wrong second factor revokes the pending session | **PROVEN** | �wrongSecondFactorRevokesTheSession |
| P-10 | Elevation is a bounded window that closes on time | **PROVEN** | �elevationIsBounded � 15 min, then refused |
| P-11 | Unknown account and wrong password are indistinguishable | **PROVEN** | �failuresLookTheSameFromOutside � same message, different audit reason |
| P-12 | A denial is audited | **PROVEN** | `�secondFactorGrantsReadButNotWrite` - the row is present after the refusal |
| P-12a | The audit survives the caller's rollback | **PROVEN, load-bearing** | Changing `REQUIRES_NEW` to `REQUIRED` turns `�secondFactorGrantsReadButNotWrite` red - the rollback is genuinely exercised, because `createClinic` is `@Transactional` and proxied in the test context, so the audit row disappears with the transaction. Verified 2026-10-13 by probe. This contradicts an earlier review opinion that the test could not distinguish the two |
| P-21 | The platform cookie carries the same Secure resolution as the customer cookie | **PROVEN** | `SecurityPropertiesCookieSecureTest` (7 cases) + `PlatformConsoleBoundaryHttpIntegrationTest.platformCookieAttributesAreRestrictive`. Added after an adversarial review found the platform cookie inlined `Boolean.TRUE.equals(null)`, which resolves to false whenever the property is unset - i.e. in production |
| P-22 | The platform password step is rate limited | **PROVEN, load-bearing** | `PlatformAuthRateLimitTest` (5 cases). Disabling the limiter check turns all 5 red; restoring returns 133/133 green |
| P-23 | A CSRF refusal terminates the request, not just the response | **PROVEN** | `�csrflessPostCreatesNothing` counts `platform_sessions` before and after. Added to settle an open review question: if the filter were also auto-registered as a servlet filter, `beginLogin` would run behind the 403 |
| P-24 | The filter is not auto-registered as a servlet filter | **PROVEN structurally** | disabled `FilterRegistrationBean` in `PlatformConsoleConfiguration`. Belt-and-braces with P-23; the registration is now impossible rather than merely unobserved |
| P-25 | The second factor with no cookie is 401, not 500 | **PROVEN** | `�secondFactorWithoutACookieIsUnauthorized`. Previously `hash(null)` raised NPE, rendering as 500 with a stack trace - reachable by any cross-site client, since SameSite=Strict withholds the cookie |
| P-13 | The audit is append-only against a privileged principal | **PROVEN** | �auditRowsCannotBeRewritten � the trigger rejects the UPDATE |
| P-26 | Elevation requires a fresh second factor | **PROVEN** | `�elevationRequiresAFreshSecondFactor` (HTTP) + `PlatformAuthService.elevate` re-verifies the TOTP. Fixed after review finding: the 15-minute window was re-openable for 8 hours, so a captured cookie bought permanent cross-tenant write. Now matches what `V20261012000000` already promised |
| P-27 | Every write path audits its capability denial | **PROVEN** | `createAccount` and `grantAccess` now record `CAPABILITY_DENIED`; previously they threw without a row, which defeated the audit''s stated purpose for two of three routes |
| P-28 | A context path cannot disable platform authentication | **PROVEN, load-bearing** | `�theContextPathDoesNotDisableTheFilter`. Reverting to `getRequestURI()` turns it red. Skipping the filter means *no* authentication, the dangerous direction to fail in |
| P-14 | The TOTP secret is never projected | **PROVEN** | �secretIsNeverProjected � asserted on record components, not values |
| P-15 | A clinic is created with its first admin, or not at all | **PROVEN** | �creatingAClinicAlsoCreatesItsAdministrator � counts read back from the database |
| P-16 | A clinic cannot be created in an organization that does not exist | **PROVEN** | �unknownOrganizationIsRefusedAndAudited |
| P-17 | Disabling the console leaves **no route**, not a guarded one | **PROVEN** | PlatformConsoleDisabledTest.noPlatformRouteIsMapped |
| P-18 | No console bean is created without the switch | **PROVEN** | �consoleBeansAreAbsent |
| P-19 | No console class carries an *ungated* stereotype | **PROVEN** | �consoleClassesAreNotUngatedComponentScanned |
| P-20 | The platform login steps are CSRF-protected | **PROVEN** | �theThreeStepsWorkOverHttp � step two without a token is 403 |

### Rows that are deliberately absent

No endpoint executes SQL, runs a shell, returns a secret, or mutates payment/billing state. These
are not rows marked complete; the capabilities do not exist, so there is nothing to assert.
