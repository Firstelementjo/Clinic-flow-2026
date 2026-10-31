# DEC-006 — Governed clinic AI: autonomy, approved knowledge, triage protocol, escalation

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinic answer recorded; **and a pre-cutover ruling is required that is not this decision** (§2)
- **Owner:** Clinic **and** clinical lead jointly. No engineering owner — engineering may implement the mechanism but may not decide the clinical latitude
- **Approvers required:** clinical lead (triage protocol, concerning-symptom handling, what the assistant may say about a clinical matter); clinic owner (which actions may run unattended, escalation destination and clock); legal / data-protection adviser (what patient data may be captured in a conversation and retained); **the owner of ADR-0007 must co-sign any amendment to Part 2**; engineering must not edit ADR-0007
- **Blocks:** Clinic Phase 10 in full; also Phase 4 (the AI must be able to read the availability service), Phase 6 (`send_confirmation` is an AI workflow), and the cutover ruling in §2
- **Related:** **ADR-0007 Part 2** (`docs/migration/adr/0007-finance-and-ai-policy.md:59-94`) — the rule this decision implements, not replaces; DEC-013 (AI asleep until enabled and paid for; fail-closed); DEC-001 (which slots the AI may offer), DEC-003 (an AI-proposed cancellation obeys the notice window), DEC-005 (the AI is a sender and is bound by consent), DEC-007 (what the AI may put into a prompt, and what is retained), DEC-010/ADR-0007 (no prescribing), DEC-015/ADR-0022 (Patient is not Customer), DEC-004 (`0007:70` places financial change in the consequential class)

> **This document decides nothing.** `firstelement-dental-clinic-feature-kit/AGENTS.md:19`: *"Never invent
> clinic policies for … **triage** … Record an open decision."* `DENTAL_CLINIC_REQUIREMENTS.md:185`:
> *"The protocol must be approved; **the model must not invent medical triage**."* `plan.md:298` makes it a
> Phase 10 work item: *"Add approved concerning-symptom triage protocol **only after clinic sign-off**."*

---

## 1. Question

Four sub-questions, taken from the source row **«الذكاء الاصطناعي»** (*artificial intelligence*), verbatim:

> «ما الذي يُسمح للمساعد بتنفيذه دون موظف؟ ما بروتوكول الأعراض المقلقة؟ ما الأسعار والمعلومات المعتمدة
> للإجابة عنها؟»
> *(What may the assistant be allowed to perform without a staff member? What is the protocol for
> concerning symptoms? What are the approved prices and information it may answer about?)*

Plus a fourth the register names explicitly — **escalation** (`open-decisions.md:111`) — which the source
asks about twice and never answers:

| # | Sub-question | Kind of answer | Who owns it |
|---|---|---|---|
| **A** | What may run **without a human**? | A risk classification | Clinic + clinical lead |
| **B** | Which prices and pieces of information are **approved to be answered**? | A publication decision | Clinic (commercial) + legal (what may be published) |
| **C** | What is the **concerning-symptom protocol**? | A clinical protocol | Clinical lead, alone |
| **D** | What is the **escalation** path, its destination, its clock, and what happens if nobody answers? | An operational commitment | Clinic owner |

### 1.1 What the source already settles, so it need not be re-asked

| Settled | Text |
|---|---|
| The assistant executes **only** permitted actions **through system services** and must not bypass booking rules or access permissions | §4 preamble: «يجب أن ينفّذ فقط الإجراءات المسموح بها **من خلال خدمات النظام**، وألا يتجاوز قواعد الحجز أو صلاحيات الوصول» |
| It must **acknowledge not knowing** rather than invent | §4.1: «الاعتراف بعدم معرفة الإجابة عند غياب المعلومة **بدل اختراعها**» |
| It must hand unclear and staff-required questions to the clinic team | §4.1: «تحويل الأسئلة غير الواضحة أو الحالات التي تتطلب موظفًا **إلى فريق العيادة**» |
| It must not diagnose or independently prescribe | §4.1: «**عدم تقديم تشخيص طبي أو وصف علاج بشكل مستقل**» |
| It must confirm only after the write succeeded | §4.2 item 6, and `AGENTS.md:17`: *"AI cannot claim an appointment succeeded before transactional persistence succeeds"* |
| Triage follows a **clinic-approved** protocol | §4.1: «وفق **بروتوكول فرز معتمد من العيادة**» |

So the clinic is not being asked whether the AI may diagnose. It is being asked **how far the
assistant may go on everything else**, and the source has already drawn the clinical line.

---

## 2. ⚠️ The pre-cutover ruling: a second booking path approves appointments with no human

**This is not part of DEC-006's question, and it must not wait for DEC-006. It is a product decision
about a code path that is already deployed, and it is the single most urgent item in this brief.**

### 2.1 The claim, verified

The task brief asserted a second, independent appointment-booking implementation reachable from the AI
tool executor that calls `prisma.appointment.create` and hardcodes `status: 'APPROVED'`. **I read the
files and it holds, on every point.** Verified:

| Claim | Evidence |
|---|---|
| `createAppointmentValidated` exists and **writes an appointment row** | `packages/ai/src/tools/clinic-scheduling.ts:512`, `prisma.appointment.create` at `:572-591` |
| …with **`status: 'APPROVED'` hardcoded** in the create payload, and again in the returned object | `clinic-scheduling.ts:588` and `:621` |
| `rescheduleAppointmentValidated` **sets `status: 'APPROVED'`** on the move | `clinic-scheduling.ts:625`, update at `:671-677`, `:675`; returned at `:703` |
| `cancelAppointmentValidated` **sets `status: 'CANCELLED'`** with no policy check | `clinic-scheduling.ts:707`, `:729-735` |
| **There is no approval step anywhere in this file.** No `ai_approvals` insert, no `humanApproved`, no request/approve round trip, no expiry. `status` is a string literal in all three writers. | whole file, `:512-762` |
| ⚠️ **An AI-created booking records itself as *not* AI-proposed**: `isAiProposed: false` | `clinic-scheduling.ts:589` |
| The audit rows *do* say `actorType: 'AI_AGENT'` — so the provenance exists in the audit log while the appointment row denies it | `clinic-scheduling.ts:598,684,742` |
| The tool definitions carry **no approval class at all.** `create_appointment` is `mutates: true, isImplemented: true` and declares no `consequential` flag, no `requiresApproval`, no field allow-list. **The concept does not exist in the legacy tool vocabulary.** | `packages/ai/src/tools/definitions.ts:212-233`; whole file searched |
| The executor's only gate is **membership in the agent version's `enabledTools` list** | `executor.ts:71-73`; `agents.service.ts:78` filters to names present in `TOOLS` |

### 2.2 The reachability chain, verified end to end

```
apps/api/src/channels/webhooks.controller.ts:55          injects MessageQueueService
  → apps/api/src/messaging/message-queue.service.ts:39-40,103   AIOrchestratorProvider, orchestrator.run(...)
    → apps/api/src/ai/ai.providers.ts:2,15               AIOrchestratorProvider extends AIOrchestrator (@firstelement/ai)
      → packages/ai/src/orchestrator/orchestrator.ts:78  this.tools = new ToolExecutor(prisma)
        → packages/ai/src/tools/executor.ts:295-323      case 'create_appointment' → createAppointmentValidated(...)
          → packages/ai/src/tools/clinic-scheduling.ts:512,572,588   prisma.appointment.create({ status: 'APPROVED' })
```

Every hop above was read in this session. The only precondition is that the clinic's project has an
enabled AI agent whose active version lists `create_appointment` in `enabledTools`
(`orchestrator.ts:107-118`; `agents.service.ts:78,111,139`).

> **One correction to the read-only port inventory, and it sharpens rather than softens the finding.**
> `docs/clinic/appointment-port/README.md:543,568-570` frames this as *"if the legacy runtime is still
> deployed and reachable"* and *"a live-system question for the orchestrator"*. The repository's own
> deployment configuration **is** that runtime:
>
> ```
> deploy/fly/Dockerfile:19     CMD ["sh","-c","node apps/api/dist/main.js & node apps/worker/dist/main.js & wait"]
> deploy/fly/backend.fly.toml:1,26   app = "firstelement-backend"; internal_port = 4000
> ```
>
> The Fly image builds `apps` and `packages` (`Dockerfile:5-10`) and runs `apps/api`, which is the
> process that transitively imports `@firstelement/ai`. **There is no separate Spring deployment
> configuration in this repository** — `deploy/fly/` contains exactly three apps: `backend`, `postgres`,
> `redis`.
>
> ⚠️ **What I still cannot verify:** deployment configuration is not a running deployment. I did not
> contact fly.io, did not read any environment, and cannot say whether `firstelement-backend` is
> currently running, for which tenants, with which agent versions. **That is the live-system question,
> and it is the orchestrator's to answer, not mine.**

### 2.3 What this contradicts

| Source | What it says |
|---|---|
| `PROJECT_REQUIREMENTS.md:140` (FR-5 clause 9) | *"Customer-visible responses, financial changes, permission changes, deletion, and external side effects require **explicit AI policy**. **High-risk actions require human approval.**"* |
| `firstelement-dental-clinic-feature-kit/AGENTS.md:16` | *"A dentist is the clinical authority. AI and software may assist; they **do not diagnose, prescribe, approve treatment**, or replace professional assessment."* |
| ADR-0007 Part 2 action-class table (`:70`) | Booking or rescheduling is **consequential**: *"human approval required; the model may only **request** the action."* |
| ADR-0007 Part 2 rule 7 (`:92-94`) | *"Every AI action is attributable to … **approvals**, refusal, override, and final disposition."* |

### 2.4 The Spring runtime does the opposite, on purpose — verified

| Claim | Evidence |
|---|---|
| `AppointmentProposalTool.perform` **throws unconditionally**, by design | `AppointmentProposalTool.java:100-106`, and the class note at `:18-22` names ADR 0007 Part 2 as the reason |
| It is declared `consequential = true`, `AiActionClass.CONSEQUENTIAL`, risk `MEDIUM` | `AppointmentProposalTool.java:87-91` |
| The Spring catalogue registers **exactly two** tools and refuses to start if a body does not expose a declaration | `AiToolCatalog.java:74-83` |
| The Spring executor cannot run a consequential body without `AiApprovalService.consume` returning true, and there is **no auto-approve timeout** | `AiToolExecutor.java:64-68`, `:248-252` |
| The approval state machine is real: `request`, `approve`, `reject`, `revoke`, `consume`, `expire`, `require`, with schema constraints including *no self-approval* | `AiApprovalService.java:193,236,292,339,365,426,431`; `:59,70` |
| `ai_approvals` requires `aiRunId uuid NOT NULL` and carries `actionClass`, `status DEFAULT 'PENDING'`, `decidedByUserId`, `decidedAt`, `expiresAt` | `V20261005100000:632-650` |
| ⚠️ **…but there is no route to approve one.** The Admin surface exposes only reads: `@GetMapping("/ai/approvals")` and `@GetMapping("/ai/approvals/{approvalId}/decisions")`. No `@PostMapping` anywhere in `backend/` targets an AI approval. | `AdminReadModelController.java:384,398`; verified by searching every `*Controller.java` in `backend/` for an approval mapping |
| Neither Spring runtime can book at all — the only two `insert into appointments` in the Spring tree are test fixtures | `ClinicFixture.java:188`; `CustomerListProjectionIntegrationTest.java:540` |

**Read together, this is the precise state of the system:** the target runtime enforces approval and
cannot book; the deployed runtime can book and has no approval concept. Neither one satisfies
`PROJECT_REQUIREMENTS.md:140`.

### 2.5 The ruling that is required, and its three honest options

| Option | Means | Cost | Forecloses |
|---|---|---|---|
| **P1 — Remove the AI write path before cutover** | `create_appointment`, `reschedule_appointment` and `cancel_appointment` are removed from the legacy executor, or the legacy runtime is retired, **before** the clinic vertical is exposed | Removes a capability someone may believe is in use; needs a check for live agent versions that enable them | Nothing that the target runtime does not already replace with something more governed |
| **P2 — Gate it behind a human approval in the legacy runtime first** | The legacy path gains the same request/approve round trip the Spring path already has | Weeks of work on code that P1 would delete; the approval would land in `packages/ai`, which has no `ai_approvals` equivalent today | Nothing, but it is the expensive way to buy the same safety |
| **P3 — Leave it, and record the clinic's explicit acceptance in writing** | The clinic is told, in writing, that an AI can book and self-approve in the deployed runtime, and accepts that for a period | Zero engineering | **It does not foreclose anything technically, and it is almost certainly the wrong answer.** It should be recorded as available so the decision is visibly made rather than quietly inherited |

> **Engineering's position, offered as input and not as a ruling:** **P1**, on the grounds that the
> target runtime already encodes the correct control and the deployed one does not, and that the
> cheapest place to remove a capability is before it has clinic data behind it. **But this is a product
> decision about a live system, not a code cleanup, and it is the product owner's to make.** Answering
> DEC-006 does **not** answer it: DEC-006 governs what a *governed* assistant may do; §2 governs whether
> an *ungoverned* one may keep doing something. They are different questions with different owners.

---

## 3. Why it blocks

### 3.1 The Phase 2 tables it touches

| Table (`plan.md`) | What DEC-006 fixes about it |
|---|---|
| *(unnamed)* **approval table** | `plan.md:134` names "clinic audit, outbox, idempotency, **approval**, and read-model tables" without naming one. **The existing `ai_approvals` is scoped to an AI run** (`aiRunId NOT NULL`, `V20261005100000:636`) and therefore **cannot** carry a human staff approval of a clinic cancellation (DEC-003) or of anything the AI did not do. This is the same gap DEC-003 §3.5 records from the other side. |
| **`reminder_policies`** (`plan.md:130`) | If `send_confirmation` is an AI workflow (`plan.md:292`), who owns the policy — the clinic's or the agent's? |
| **`service_catalog_items`** (`plan.md:128`) | Sub-question B: whether the AI may quote a price, and from which column. Today the only price is `procedures.price double precision` — **a float with no currency, no effective date and no version** (`baseline:1185`), and it is on the *procedure*, not on a *catalogue item*. |
| **`appointments`** (`plan.md:130`) | Sub-question A: whether an AI-proposed booking is `PENDING` or something else. `appointments.isAiProposed` is **one boolean with `DEFAULT true`** (`baseline:627`), and the register already records that *"A default-on flag in this schema would quietly run AI for a clinic that never paid"* (`open-decisions.md:78-80`). A boolean is not a taxonomy (traceability A-6). |

### 3.2 The phases it blocks

- **Phase 10** — the whole phase. `plan.md:290` (bind to approved knowledge), `:291` (`get_available_slots`
  read tool), `:292` (the four typed workflows *with policy checks*), `:293` (recheck availability,
  record source as AI), `:294` (confirm only after persistence), `:296` (identity verification),
  `:297` (human handoff summary), `:298` (**triage, only after sign-off**), `:299` (**clinical
  retrieval and clinical suggestions disabled by default**). The Phase 10 gate (`plan.md:303`) requires
  eleven named tests, of which *"prohibited-diagnosis"* and *"human handoff"* cannot be written before
  A, C and D are answered.
- **Phase 4** — the AI offers slots from the single availability service (`plan.md:175`). Until DEC-001
  lands, the AI cannot be given a duration rule to offer against.
- **Phase 6** — `send_confirmation` is a send. DEC-005's consent answer binds it.
- **Phase 12** — may not enable a customer-visible AI action lacking the policy, approval and audit
  trail ADR-0007 requires (`0007:109-110`).

---

## 4. What already exists — verified, not assumed

### 4.1 Citation convention

`baseline:NNN` = `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql`. Arabic quotes are
cited by **section and row label**, not by paragraph index — see the warning below.

> ⚠️ **Arabic paragraph numbering in the earlier briefs does not reproduce.** DEC-002, DEC-003,
> DEC-004, DEC-007, DEC-009 and DEC-010 cite `source ¶N`. Re-extracting
> `firstelement-dental-clinic-feature-kit/source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx` →
> `word/document.xml` gives **322** `<w:p>`, **315** with text, **0** `<w:drawing>` and no
> `word/media/`; the offsets against those briefs are **not constant** (2 at the odontogram row, 6 at the
> open-questions table). This brief cites by **row label**. Reported as a follow-up; the other six briefs
> were not edited.

### 4.2 The autonomy controls that exist — and the ones that do not

| Claim | Evidence |
|---|---|
| **There are no autonomy levels and no per-project control.** The only switch is a deployment-global boolean `firstelement.ai.enabled` | `AiRagProperties.java:38` |
| The only run limit is a **flat per-tenant counter**, `runsPerMinutePerTenant`, explicitly *"deliberately coarse"* | `AiRagProperties.java:96-103` |
| A deployment ceiling exists (`maxToolCallsPerRun`) and a tenant policy ceiling exists (`ai_policies.maxToolCallsPerRun`), effective limit = the lower — so the *shape* of a per-tenant AI policy table already exists | `AiRagProperties.java:87-94` |
| ⚠️ **And the table itself is much better than the deployment-global flag suggests — verified by reading it.** `ai_policies` is **`projectId`-scoped, versioned (`currentVersionId`, `version`), bilingual-named (`nameAr`/`nameEn`, both `NOT NULL` with CHECKs)**, and carries `retentionClass`, `redactionPolicy`, `budgetId` and an ordered `failoverChain` | `V20261005100000:282-334` |
| It has **four explicit approval toggles that all default to `true`**: `customerVisibleRequiresApproval`, `financialActionRequiresApproval`, `permissionChangeRequiresApproval`, `externalSideEffectRequiresApproval` | `V20261005100000:298-301` |
| …and the DDL says why, in terms: *"The four classes ADR-0007 Part 2 names. All four default TRUE, and the defaults are the CONSERVATIVE choice … the safe default is 'ask a human', not 'do it'."* | `V20261005100000:294-297` |
| ⚠️ **The gap between that table and this decision is narrow, and it is nameable.** Four ADR-0007 class toggles exist and all fail closed; **"booking" is not one of them.** Sub-question A needs a class for *creating, rescheduling and cancelling an appointment* — which is precisely the action the deployed legacy runtime performs with no approval at all (§2) | as above, read against `clinic-scheduling.ts:588,675` |
| DEC-013 requires the opposite of a deployment-global switch: per-clinic, **asleep until enabled and paid for**, and *"missing entitlement means asleep, always"* | `open-decisions.md:65-84` |
| The per-project system prompt column **exists, is project-scoped and is read in production**, but nothing ever writes it outside tests | traceability AI-4 (`AiPolicyRepository.java:197`); ⚠️ **not re-verified here** |

### 4.3 Approved knowledge — the gap behind sub-question B

| Claim | Evidence |
|---|---|
| ⚠️ **There is no `approved` flag, no version column and no owner** on ingested knowledge; ingestion goes straight to `READY` | traceability AI-3 (`baseline:948`, `KnowledgeIngestionService.java:212`) — ⚠️ quoted from the 2026-10-12 verification, **not re-verified here** |
| **No structured-facts tool exists**, and `procedures` — which holds name, duration and price — is never read by the AI | traceability AI-3; and §2.1 above, where the legacy AI path *does* read it and that is the problem |
| The only price anywhere is `procedures.price double precision`, nullable, with no currency and no effective date | `baseline:1185` |
| ADR-0007 rule 2 already requires money to be versioned and effective-dated; a price the AI quotes is not exempt | `0007:33-35` |
| DEC-013 requires the knowledge-base flag to be checked **in retrieval itself**, or "AI without KB" silently becomes "AI with every clinic's knowledge" | `open-decisions.md:81-84` |

### 4.4 Triage and escalation — almost nothing exists

| Claim | Evidence |
|---|---|
| ⚠️ **No urgent-symptom handoff.** The AI assigns no priority, writes no escalation row and starts no clock | traceability AI-8 (`AiResponseJobHandler.java:236`) — ⚠️ quoted, not re-verified |
| The urgency engine **exists and is never called from the AI path** | traceability AI-8; the escalation engine is `packages/ai/src/scoring/escalation-engine.ts` |
| Handoff has summary and attribution but **no assignment, no SLA clock, no inbox row** | traceability AI-9 (`AdminReadModelController.java:384`) |
| **The clinic's own source names the triage protocol as a clinic-approved artefact** and forbids the model inventing one | source §4.1; `DENTAL_CLINIC_REQUIREMENTS.md:185`; `plan.md:298` |
| ⚠️ **There is no triage protocol in the repository.** No symptom list, no severity vocabulary, no destination map | grep across `backend/**`, `packages/**`, `apps/**` |

### 4.5 What the source requires of the assistant that the code does not yet do

| Source (`source` §4) | Requirement | State |
|---|---|---|
| «يعرض الأوقات المتاحة فعلًا» — offer only real available times | no invented times | **Blocked on DEC-001/Phase 4**, not on this decision |
| «إنشاء الحجز عبر وظيفة نظامية معتمدة، ثم تأكيد التفاصيل **بعد نجاح العملية فقط**» | book through a trusted system function, confirm only after success | `AGENTS.md:17` restates it; `plan.md:294` |
| «اجمع الحد الأدنى من البيانات اللازمة» — collect the minimum data | data minimisation | No identity verification exists (traceability AI-7: *"no verification of any kind"*) |
| «تحويل … إلى فريق العيادة» — hand off to the clinic team | escalation | `request_human` exists in the legacy tools (`executor.ts:209-214`) and in the Spring tool set; neither is a clinic escalation |

### 4.6 Not verified

No live database was inspected. The `requirements-traceability.md` rows are quoted from a verification
performed on 2026-10-12 at commit `f182ada` and were **not** re-run here; the ones that matter most to
this brief (AI-3, AI-8, AI-9) are marked as such above. **I did not inspect any running deployment**, so
§2.2's "is it live" question is genuinely open. No clinic representative or counsel has been consulted.

---

## 5. Options

### 5.1 Sub-question A — what may run without a human

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **L0 — Nothing autonomous** | Every action is proposed and a human approves | Matches ADR-0007 exactly; matches what the Spring runtime does today | Convenience. A clinic that answers "nothing" has lost nothing that exists |
| **L1 — Read-only autonomy** | The AI may look up availability, prices, hours and location; every write is proposed | The cheapest useful level; the read tools have no side effect to get wrong | Nothing — L2 and L3 are additions |
| **L2 — Tiered per clinic** | The clinic sets, per project, which actions may run unattended; a `consequential` action is named and its blast radius stated | A per-clinic configuration surface DEC-013 already expects; a per-action risk classification the clinic must own | A single global level |
| **L3 — Action-class driven** | The class comes from the action's declared class, not from a clinic toggle — booking is always consequential, "look up a price" never is | No clinic configuration to get wrong | A clinic that wants to allow unattended booking must change the action's class, which is a code/ADR change, not a setting |

**Engineering note, not a ruling:** L3 is what ADR-0007 already describes (`0007:67-71`), and the Spring
runtime already encodes it (`AppointmentProposalTool.java:91`). **And the per-clinic half of L3 already has
a table**: `ai_policies` is project-scoped, versioned, bilingual-named, and carries four
`…RequiresApproval` toggles that all default to `true` (`V20261005100000:282-334`). The missing piece is
**one more class** — booking, reschedule, cancel — and an authoring surface nobody has built. L2 is what
DEC-013's per-clinic model would naturally produce. **L1 is the only level that is safe to ship before
the clinic answers**, because it is the only one whose worst case is a wrong answer rather than a wrong
action.

### 5.2 Sub-question B — approved prices and information

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **K1 — Nothing is approved** | The assistant says it does not know | Nothing to build; matches «الاعتراف بعدم معرفة الإجابة» exactly | Convenience. Nothing substantive |
| **K2 — Structure plus an approval state** | Prices, hours, location, services, payment methods and cancellation policy come from **typed fields** (not free text), each with an `approved` flag, an owner and an effective date, and an unapproved field is not retrievable | Requires the `approved`/`version`/`owner` columns the knowledge path lacks today (§4.3), plus an approval surface | Free-text answers. The source's list is closed and short, so this is a small type, not a document store |
| **K3 — Publish as documents** | The approved answers live as reviewed knowledge documents the AI quotes with citations | Reuses retrieval and citation | Precision. A quoted sentence cannot answer "what does the cleaning cost", only "what did the policy page say" |

### 5.3 Sub-question C — the concerning-symptom protocol

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **X1 — No triage; every concerning message escalates** | The AI recognises nothing; anything it cannot confidently answer goes to a human | Simplest; safe | Speed. Every "is this an emergency?" is a human decision |
| **X2 — The clinic supplies a closed, reviewed protocol** | A seeded symptom→action table the clinic writes and approves, with a named destination and a clock per entry | The clinic must author it (it is a clinical artefact); it needs an owner and a review date | Nothing, and it is the target |
| **X3 — Protocol plus a hard refusal list** | As X2, plus phrases the assistant must never answer at all | X2's cost plus a maintained list | Nothing |

### 5.4 Sub-question D — escalation

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **E1 — Escalate and stop** | The AI hands off with a context summary; nothing else is promised | The minimum the source asks for (§4.3: a clear way to hand the conversation to a person with a context summary) | Any service commitment |
| **E2 — Escalate with a destination and an SLA clock** | A named role, a queue, a clock, and an alert if nobody answers | Needs an assignment target and an SLA policy DEC-003/§4.4 do not supply | Nothing |
| **E3 — Escalate with a clinical emergency override** | A named subset of symptoms bypasses the queue and routes to a phone number or an on-call clinician | The most operationally demanding, and the one with the highest consequence if the routing is wrong | Nothing, but it cannot be built without E2 |

---

## 6. Recommendation — **engineering recommendation, requires clinical and clinic sign-off**

**Ship L1 + K1 + X1 + E1 before the clinic answers. Build the mechanism so that L2/K2/X2/E2 are
configuration over a mechanism that already exists. Keep the target runtime's existing approval control
exactly as it is.**

1. **Do not weaken `AppointmentProposalTool`.** Its `perform` throws unconditionally and its class note
   explains why: *"A body that could perform would be one refactor away from executing, and the schema's
   `ai_tool_executions_allow_consequential_needs_approval_check` would then be the only barrier"*
   (`AppointmentProposalTool.java:18-22`). That is the correct design and DEC-006 must not reopen it.
2. **Build the missing route, not a missing control.** The approval state machine exists and is tested
   (`AiApprovalService`, `ApprovalLifecycleIntegrationTest`); what does not exist is a **write route**
   (`AdminReadModelController` exposes only reads, `:384,398`). Adding `POST /admin/ai/approvals/{id}/approve`
   — permissioned, audited, and refusing self-approval as the schema already enforces — is the single
   highest-value Phase 7/10 item, because without it an approval is a dead end. **This is an engineering
   task, not a clinic decision, and it does not wait for DEC-006.**
3. **Make the approval table generic or explicitly not.** `ai_approvals.aiRunId` is `NOT NULL`
   (`V20261005100000:636`), so a clinic approval that has no AI run behind it cannot be stored there. If
   DEC-003 or DEC-005 needs staff approval of something the AI did not request, `plan.md:134`'s unnamed
   "approval" table has to exist. Recorded in §10.
4. **Add the `approved` / `owner` / `effective` columns now, as inert nullable columns.** Sub-question B
   is a publication decision; the columns cost nothing unset and cost a migration on knowledge data if
   added later. This is the same "columns only, behaviour after the answer" split DEC-007 §5 and DEC-003
   §5.3 recommend, applied a third time — and the same argument DEC-009 §6 makes for seed catalogues
   rather than enums.
5. **Add one class to `ai_policies`, not a new configuration surface.** `ai_policies` is already
   project-scoped, versioned, bilingual-named, retention-tagged and redaction-tagged, and already
   carries four `…RequiresApproval` toggles that default to `true` (`V20261005100000:282-334`). Sub-question
   A is best modelled as a fifth toggle — `bookingRequiresApproval`, default `true` — beside four that
   already fail closed, **plus** the action-class declaration the Spring runtime already carries
   (`AppointmentProposalTool.java:87-91`). The class a code review enforces is primary; the per-clinic
   toggle is the ceiling on top. Neither alone is right: a class the clinic cannot override is not
   configurable, and a clinic toggle with no class underneath is a switch that can turn "consequential"
   off.
6. **Treat the concerning-symptom protocol as clinical content, not as prompt text.** It belongs in
   seeded, versioned, clinic-approved data with an owner and a review date, exactly as DEC-009 recommends
   for finding vocabularies. A triage protocol in a system prompt is a protocol nobody reviewed and nobody
   can update.
7. **Replace `isAiProposed` as a source of truth.** The column defaults to `true` (`baseline:627`), so any
   row created without an explicit value claims AI provenance, and the legacy AI path sets it to `false`
   while writing an `AI_AGENT` audit row (`clinic-scheduling.ts:589` vs `:598`). Provenance must come
   from the audit trail, which is correct, not from a boolean a caller can choose.
8. **Do not let DEC-006 authorise §2's outcome.** The ruling in §2 is about a deployed runtime, not
   about this decision's content, and it has a different owner.

**What the clinic and clinical lead must still supply, and this brief does not supply:** the autonomy
levels, the approved knowledge list and its owner, the triage protocol and its review cycle, and the
escalation destination and clock. Nothing here is an answer to any of those.

---

## 7. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| **The §2 path left reachable and exercised** | Appointments booked and self-approved by a model, recorded as **not** AI-proposed, in a runtime the clinic believes is governed | **No.** A patient believes the clinic approved their appointment. The audit row disagrees with the appointment row, so the record is internally inconsistent as well as wrong |
| A booking by a model treated as **not** consequential because "it rechecked availability" | The control has been redefined after the fact, and a deterministic availability check is not a human being | **No** |
| `status: 'APPROVED'` written at creation, as the legacy path does | Every AI booking lands in an approved state that no human ever saw. Reception cannot distinguish an AI booking from a confirmed one | **No** for the rows already written |
| Autonomy set as a **per-clinic toggle** with no class underneath | A consequential action is switched off by an operator who does not read the ADR | Yes — configuration. **The bookings already made are not** |
| Autonomy set as a **hard-coded class** with no clinic ceiling | Every clinic behaves identically; a clinic that wants more autonomy cannot get it without a release | Yes, additively |
| Approved prices taken from `procedures.price` (a float, no currency, no effective date) | The AI quotes a number the clinic cannot defend, and cannot say when it was last reviewed | **No** — a quote is made to a patient and acted on |
| Knowledge `approved` columns baked as a `CHECK` or an enum | Per-clinic approvals become a migration | **No** |
| Triage written into the system prompt | A clinical protocol that no clinician reviewed and no one can version; it changes silently on the next prompt edit | **No** — and it is exactly what `plan.md:298` forbids |
| Triage shipped with an LLM-invented urgency threshold | A patient with a concerning symptom is told to wait, because the model scored the message below a line nobody approved | **No** |
| Escalation with no destination or clock | An escalation is a dead end. The model hands off and nothing is queued, assigned or timed — which is today's state for appointments | Yes for the mechanism; **no** for the patients who waited |
| `AppointmentProposalTool` "finished" | The deliberate `throw` is removed and the **only** barrier becomes a schema CHECK — the exact refactor the class note warns against | **No** |

**The asymmetry:** policy columns, action-class vocabulary and route additions are additive and cheap.
**An appointment a patient was told was approved** is not recoverable by any migration.

---

## 8. Dependencies and conflicts

### 8.1 ADR-0007 Part 2 — this decision implements it, and must not amend it

The action-class table (`0007:67-71`) and the seven rules (`0007:73-94`) are **Accepted**
(`0007:4`) and were written before the clinic vertical existed. DEC-006 supplies the *values* the
classes need. **If a clinic answer requires changing Part 2 — for example by allowing unattended
booking — the correct instrument is a new ADR co-signed by ADR-0007's owner, not an edit.**
Engineering and this brief do not touch `0007`.

### 8.2 DEC-013 — AI is asleep until paid for, and fails closed

The gate belongs **in the trigger**, before a run row exists and before any provider is called
(`open-decisions.md:70-71`). That is the right place and it is **deployment-global today**
(`AiRagProperties.java:38`); DEC-013 requires a per-project lookup. **Note the direct interaction with
§2:** a clinic that has not enabled AI is safe on the Spring side, but the legacy runtime's own
`isEnabled` (`orchestrator.ts:112`) is the *only* thing between a webhook and a booked appointment.

### 8.3 DEC-001 and Phase 4 — the AI may only offer slots the engine agrees with

`plan.md:291` makes `get_available_slots` a read tool and `plan.md:293` requires creation to recheck
availability. Both mean **one** availability function. §2.1 is the standing proof of what happens when
there are two.

### 8.4 DEC-003 — an AI-requested cancellation is a consequential action

ADR-0007 puts it in the consequential class (`0007:70`), and DEC-003's notice window is the policy it
must obey. Neither decision closes the other by implication.

### 8.5 DEC-005 — the AI is a sender and is bound by consent

`send_confirmation` (`plan.md:292`) goes out through the same connectors and therefore through the same
consent gate. DEC-005's opt-in/opt-out answer applies to the AI exactly as it applies to a reminder job.

### 8.6 DEC-007 — what may enter a prompt

Sub-question B determines what is retrievable; DEC-007 determines what may be retained afterwards and
who may export it. Traceability AI-11 records that **no consent capture, no per-tenant opt-in and no DPA
artefact exist** — DEC-006 cannot be answered without at least the retention half of DEC-007.

### 8.7 DEC-015 / ADR-0022 — Patient is not Customer

The AI identifies a patient. Today `Customer` is the only identity, the clinic projection drops
`customerId` (traceability P-1), and the legacy path copies a customer's display name, phone and email
onto the appointment row (`clinic-scheduling.ts:581-583`). **That is PHI in a row that is not a PHI
table**, and it is a DEC-015 boundary, not a DEC-006 one — recorded, not resolved.

### 8.8 DEC-004 — money

`0007:70` already places "financial or billing change" in the consequential class. A clinic that wants
the assistant to quote a price is one step from quoting a payable amount; the line is B's and ADR-0007's.

---

## 9. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| Action-class vocabulary on the tool definitions | **Yes** | Additive enum/flag |
| Per-clinic autonomy ceiling | **Yes** | A configuration change |
| **Reducing** autonomy (L2 → L1 → L0) | **Yes** | Configuration — but only for actions not yet taken |
| **Raising** autonomy after actions were taken at the lower level | **No** | The actions already happened under the old rule |
| `approved` / `owner` / `effectiveFrom` knowledge columns | **Yes** | Additive and nullable |
| …as a `CHECK` or an enum | **No** | `ALTER` against knowledge data |
| Triage protocol as seeded, versioned, owned data | **Yes** | A seed change; but the *content* is clinical and changing it is a clinical act |
| Triage protocol in a system prompt | **No** | It was never reviewable; replacing it means re-authoring |
| `POST /admin/ai/approvals/{id}/approve` route | **Yes** | Additive |
| Removing the legacy AI write path (§2, P1) | **Yes, technically** | **No** for any appointment it already booked |
| **An appointment a model booked and self-approved** | **No** | The patient was told |
| ADR-0007 Part 2 | **Yes** — an ADR can be superseded | Which is exactly why it must be a **new ADR**, not an edit |

---

## 10. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| 1 | **Actions allowed without a human.** For each: look up availability · look up a price · answer clinic hours/location · **create** a booking · **reschedule** · **cancel** · send a confirmation · answer a cancellation-policy question. | |
| 2 | Is the autonomy level **per clinic**, per action, or a single global setting? | |
| 3 | **Approved prices and information**: which fields may the assistant answer on, who approves each, and how often are they re-reviewed? | |
| 4 | Is a price quoted from the catalogue, and must it carry the currency and the "valid from" date? (Today's only price is a bare float — `baseline:1185`.) | |
| 5 | **Concerning-symptom protocol**: does the clinic have one today? If not, who authors it, who approves it, and how often is it reviewed? | |
| 6 | What must the assistant **never** answer, regardless of confidence? | |
| 7 | **Escalation**: to whom, in which channel, within what time, and what does the patient hear while they wait? | |
| 8 | What happens if nobody answers an escalation — retry, re-route, or a recorded no-action state? | |
| 9 | **Identity**: what minimum data must the assistant collect before discussing or changing an appointment, and how is the patient verified? | |
| 10 | May the assistant mention a patient's clinical history at all, and if so whose consent covers it? (Cross-link DEC-007 and DEC-015.) | |
| 11 | Which languages does the assistant answer in, and does a patient-facing AI reply carry the clinic's name and a human contact route? | |
| 12 | Who is accountable when the assistant is wrong — the clinic, the platform, or the reviewer of the approved content? | |

Approver, date, and whether each answer is reversible: ______________________

**Follow-up for the orchestrator (not mine to edit):**

1. **§2 ruling, needed before cutover and independent of DEC-006.** `packages/ai`'s
   `create_appointment` / `reschedule_appointment` / `cancel_appointment` write appointments with
   `status: 'APPROVED'` and no approval step (`clinic-scheduling.ts:588,675`), reachable from an inbound
   provider webhook, and **`deploy/fly/Dockerfile:19` runs `apps/api` as the `firstelement-backend`
   process**. Options P1/P2/P3 in §2.5; engineering recommends P1. **This is a product decision about a
   possibly-live system and it is not waiting on the clinic.**
2. **Add the missing approval route.** `AiApprovalService` is complete and tested; `AdminReadModelController`
   exposes only `GET /ai/approvals` and `GET /ai/approvals/{id}/decisions` (`:384,398`), and **no
   `@PostMapping` in `backend/` targets an AI approval**. Until one exists, every approval the Spring
   runtime records is a dead end.
3. **`ai_approvals` cannot hold a non-AI approval** (`aiRunId NOT NULL`, `V20261005100000:636`), and
   `plan.md:134` names an "approval" table without naming it. DEC-003 and DEC-005 both need it.
4. **`ai_policies` is the natural home for a per-clinic autonomy ceiling, and it is already built and
   constraint-checked** — project-scoped, versioned (`currentVersionId`/`version`), bilingual
   (`nameAr`/`nameEn`), with `retentionClass`, `redactionPolicy` and four approval toggles defaulting to
   `true` (`V20261005100000:282-334`). Confirm before designing anything new. **Two things are
   unverified and both are worth checking:** whether any authoring surface writes this row outside
   tests, and whether the *booking* class is missing or merely unexercised (§4.2).
5. `deploy/fly/` contains **only** `backend`, `postgres` and `redis` apps — **there is no deployment
   configuration for the Spring backend in this repository**, while the one named `backend` deploys
   NestJS. Whether the target runtime has any deployment story is a question for the orchestrator.

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007). If any answer would change ADR-0007 Part 2, that is a **separate** ADR co-signed
by ADR-0007's owner, not an amendment to `0007`.
