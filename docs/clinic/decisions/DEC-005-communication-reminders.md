# DEC-005 — Clinic communication: channels, response languages, reminder timings, sending hours

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinic answer recorded
- **Owner:** Clinic (operations), with the clinical lead for anything that becomes a patient instruction
- **Approvers required:** clinic owner / authorized manager (channels, sending hours, reminder cadence); reception lead (what is actually workable at the desk); legal or the clinic's data-protection adviser (**sending hours and opt-out are a communications/consent question, not a preference**); the owner of the messaging architecture must co-sign any channel change that adds a connector
- **Blocks:** Clinic Phase 2 (`reminder_policies`, `reminder_attempts`, `patient_consents`), Phase 6 (notifications, confirmations and reminders), Phase 12 routes, and the outbound consent check the traceability document marks MISSING
- **Related:** DEC-007 (retention and export authority — a communication record is a record), DEC-003 (a cancellation notice is a message and a reply is a cancellation request), DEC-001 (a reminder is scheduled relative to a duration), DEC-002/005 channel capability vs. clinic intent, DEC-006 (the AI sends confirmations and answers in-channel), ADR-0018 (a clinic is a `Project`)

> **This document decides nothing.** `firstelement-dental-clinic-feature-kit/AGENTS.md:28` places
> reminders inside the durable-worker rule, and `plan.md:5` makes unresolved clinic decisions *"explicit
> gates"*. The Arabic source itself defers the channel answer to the clinic — the open-questions table is
> where the clinic's own draft puts this question. Every option below is a genuine choice.

---

## 1. Question

Which **channels** the clinic will use for patient communication, in which **languages** it replies,
**when** reminders fire, and **what hours messages may be sent**. `source` row **«قنوات التواصل»**
(*communication channels*), verbatim:

> «ما القنوات المعتمدة (واتساب، رسائل نصية، اتصال، بريد)؟ ما لغات الرد؟ ما ساعات إرسال التذكيرات
> المسموحة؟»
> *(Which channels are approved — WhatsApp, SMS, call, email? What are the response languages? What
> hours are reminders allowed to be sent?)*

Three sub-questions are **not** in that row and ride on it, because the source states them as
requirements elsewhere and they cannot be answered separately:

| # | Sub-question | Why it rides here |
|---|---|---|
| **E** | **Consent and opt-out.** Does a patient have to agree to be messaged, can they stop, and does stopping mean "stop everything" or "stop this channel"? | The source makes it a rule, not a question: «احترام موافقات المريض وتفضيلاته ومتطلبات القناة» (`source` §4.3) |
| **F** | **Replies.** When a patient replies "تأكيد" or "تغيير", does the system act on it or raise it for a human? | «إتاحة ردود مثل تأكيد الموعد أو طلب تغييره أو إلغائه **إذا كانت القناة تدعم ذلك**» (`source` §4.3) |
| **G** | **Opt-out vocabulary.** What does "stop" *mean* in each language the clinic uses? | See §3.5 — this is not hypothetical; it is what the current scoring does |

---

## 2. ⚠️ Answering DEC-005 does **not** unblock reminders

State this plainly, because the natural reading of the register is the opposite:

> **Reminders are blocked on an engineering change to the outbox write API, not only on this decision.
> DEC-005 supplies the *values*; it cannot supply the *mechanism*, and the mechanism does not exist.**

Verified:

| Claim | Evidence |
|---|---|
| `OutboxRepository.ENQUEUE` writes `nextAttemptAt` as a **literal `(now() AT TIME ZONE 'UTC')`**. There is no bind parameter for it. | `OutboxRepository.java:44-58`, specifically `:53` |
| `enqueue(OutboxIntent)` passes **twelve parameters and none of them is a not-before time** | `OutboxRepository.java:292-308` |
| `OutboxIntent` — the record every producer constructs — has **no not-before field at all**; its ten components are id, tenant, project, aggregate, type, payload, key, correlation, causation | `OutboxIntent.java:31-40` |
| The only statements that ever move `nextAttemptAt` afterwards are **retry** (now + backoff, `:110`), **lease reap** (now, `:153`) and **replay** (now, `:174`) — all three are failure/recovery paths, none is a schedule | `OutboxRepository.java:107-119,147-166,168-182` |

**The refinement to the cartographer's finding, and it is a narrowing, not a disagreement.** The
port inventory records this as *"a schema/API change, not a service method"*
(`docs/clinic/appointment-port/README.md:500,559-561`). The conclusion is right; the mechanism is
narrower than stated, and this matters because it changes who has to be in the room:

- **No DDL is required.** `outbox_events."nextAttemptAt"` **already exists** with a sensible default
  (`V20261004000100:240`) and is **already the claim predicate**: `and "nextAttemptAt" <= (now() AT
  TIME ZONE 'UTC')` (`OutboxRepository.java:78`, and the same predicate in `DUE_COUNT` `:205` and
  `OLDEST_DUE` `:218`). A row written with `nextAttemptAt = now() + 24h` simply is not claimable until
  then, and it is covered by `outbox_events_claim_idx ("organizationId","state","nextAttemptAt","leaseUntil")`
  (`V20261004000100:291`). **There is also an in-repo precedent for a nullable, default-free
  `nextAttemptAt`** — `export_jobs` (`V20261005070000:183`).
- **The Java API must change**: add a not-before to `OutboxIntent`, bind it in `ENQUEUE`, and
  propagate it through every producer. That is ordinary code, on the critical path of every phase that
  enqueues work, and it is **owned by the worker-runtime maintainers, not by this decision**.
- **A queue name must be added.** `QueueName` is a **closed enum by design** (`QueueName.java:7-14`)
  and has **no reminder member** (`:26-48`). Adding `CLINIC_REMINDER` is additive to the vocabulary but
  it is the documented way this codebase avoids a producer/consumer name drift.

**So: answering DEC-005 does not unblock reminders; it unblocks the *values*.** Recorded for the
orchestrator in §10 — I do not own `worker-runtime`, `QueueName` or the plan.

---

## 3. Why it blocks

### 3.1 The Phase 2 tables it unblocks

| Table (`plan.md`) | What DEC-005 fixes about it |
|---|---|
| **`reminder_policies`** (`plan.md:130`) | **Does not exist.** Its whole content is DEC-005's: which appointment types trigger a reminder, how many, at what offsets, in which language, over which channel, and inside which sending window. Whether a policy is one row per clinic or one row per (clinic × service × channel) is the grain decision. Also whether a policy row is **effective-dated** (`plan.md:138` requires effective timestamps) so that changing the reminder cadence does not rewrite last quarter's record of what was sent. |
| **`reminder_attempts`** (`plan.md:130`) | **Does not exist.** One row per *attempt*, not per reminder — the source asks for delivery state («تسجيل حالة الرسالة عند توفرها، مثل أُرسلت أو تم التسليم أو فشل الإرسال»), the Phase 6 gate asks for deduplication (`plan.md:217`), and the retry/dead-letter machinery that would drive a retry exists in the outbox but is not connected to a patient. Its grain decides whether deduplication is a `UNIQUE` or an application check. |
| **`patient_consents`** (`plan.md:127`) | **Does not exist** (`requirements-traceability.md:34`, row P-5: *"no consent column or table"*). DEC-005's sub-question E is what it stores: per-channel consent, with a withdrawal timestamp. ⚠️ `plan.md:127` lists `patient_consents` and `plan.md:132` lists `treatment_consents` — two similarly named tables for two different consents (DEC-010 already flags this; it is re-flagged here because the *communication* one is DEC-005's). |
| **`appointments`** (`plan.md:130`) | Whether the appointment row carries the language and channel the confirmation was sent in, or whether that lives only on the attempt rows. |

### 3.2 The phases it blocks

- **Phase 6** — the whole phase. `plan.md:212` work item 1 (enqueue confirmation after commit); `:213`
  work item 2 (*"Add clinic-configurable reminder schedules and allowed sending windows"* — **this is
  DEC-005 verbatim**); `:214` work item 3 (consent, preferences, channel capabilities); `:215` work item 4
  (reply handlers); `:216` work item 5 (delivery state and alert reception); `:217` work item 6
  (deduplication). The Phase 6 gate (`plan.md:222`) requires that *"a failed message does not pretend
  success or delete the appointment"*, that *"duplicate events do not create duplicate reminders"*, and
  that *"delivery state is visible and audited"*.
- **Phase 2** — the two reminder tables and the consent table, which are Phase 2 schema and therefore
  on the migration path before any Phase 6 code exists.
- **Phase 12** — the reception routes that show reminders and reply actions.
- **Phase 10** — `send_confirmation` is one of the four typed AI workflows (`plan.md:292`).

---

## 4. What already exists — verified, not assumed

### 4.1 Citation convention

`baseline:NNN` = `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql`. Arabic quotes are
cited by **section and row label**, not by paragraph index — see the warning below.

> ⚠️ **Arabic paragraph numbering in the earlier briefs does not reproduce.** DEC-002, DEC-003,
> DEC-004, DEC-007, DEC-009 and DEC-010 cite `source ¶N`. Re-extracting
> `firstelement-dental-clinic-feature-kit/source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx` →
> `word/document.xml` gives **322** `<w:p>` elements, **315** with text, **0** `<w:drawing>` and no
> `word/media/`, and the offsets against those briefs are **not constant** (2 at the odontogram row, 6
> at the open-questions table). This brief cites by **row label** instead. Reported as a follow-up; the
> other six briefs were not edited.

### 4.2 Channels: what exists, and what the source asked for

| Claim | Evidence |
|---|---|
| **Five** channels ship: `MOCK, WHATSAPP, INSTAGRAM, MESSENGER, TELEGRAM`. **No SMS, no email, no voice.** | `ChannelType.java:10-15` |
| The source asked for **WhatsApp, SMS, call, email**. Two of those four do not exist in any form, and two channels that do exist (Instagram, Telegram) are **not in the clinic's own list**. | source row «قنوات التواصل» |
| **WhatsApp cannot receive a free-text message outside a 24-hour customer-service window**: `sessionWindowHours = 24` and `requiresTemplateOutsideSession = true` | `ConnectorCatalog.java:34-39` |
| **The window is anchored on the last inbound message, and an absent anchor means "outside"** — *"Refusing is the cheap direction"* | `OutboundDeliveryService.java:181-204` |
| ⚠️ **The connector interface has exactly one send method: `sendText`.** There is no template method, no template name, no language, no parameters. | `MessagingConnector.java:20-58`, `:52` |
| …and the Meta adapter contains **no `template` token anywhere**; it posts to `{graph}/{version}/{phoneNumberId}/messages` | `MetaConnector.java:114,138-143` (searched for `template` across `backend/channels/.../meta/`: zero hits) |
| **Consequence, stated precisely:** because the platform requires an *approved template* outside the window and the connector can only send free text, a 24-hours-before reminder to a patient who has not messaged in is **not restricted — it is impossible today**. The traceability row calls this *"partial, thinner"* (C-3/R-6); the sharper statement is that the capability does not exist on this side of the wire either. | `ConnectorCatalog.java:35`; `MessagingConnector.java:52` |
| No SMS or email connector exists anywhere in `backend/channels/` | `backend/channels/` census |

### 4.3 Consent and opt-out

| Claim | Evidence |
|---|---|
| ⚠️ **The outbound pre-flight has three checks and none of them is consent**: (1) does the channel support `sendText`, (2) is the body within `maxTextLength`, (3) is the conversation inside the session window. | `OutboundGuard.java:32-60` — verified by reading the whole method |
| There is **no consent column and no consent table** on the patient | `requirements-traceability.md:34` (row P-5) |
| ⚠️ **Opting out is currently scored as a *cancellation intent*.** The intent patterns are `\b(cancel\|unsubscribe\|close account)\b` plus the Arabic `(إلغاء\|إيقاف الاشتراك\|إغلاق الحساب)` — *إيقاف الاشتراك* is literally "stop the subscription". | `packages/ai/src/scoring/evaluator.ts:82`; mirrored in `ConversationScoring.java:88-89` |
| So a patient who means **"stop messaging me"** lands in the same intent bucket as a patient who wants to **cancel an appointment**, and nothing in the codebase distinguishes them. | as above |
| The source makes consent a **rule**: «احترام موافقات المريض وتفضيلاته ومتطلبات القناة» — *respect patient consent, their preferences and channel requirements* | source §4.3 |
| The kit forbids bypassing it: *"bypassing staff approval, **consent**, cancellation, no-show, or payment rules"* is prohibited AI behaviour | `AGENTS.md:16-17`; `DENTAL_CLINIC_REQUIREMENTS.md:217` |

### 4.4 Delivery state

| Claim | Evidence |
|---|---|
| `MessageDeliveryStatus` has five members: `PENDING, SENT, DELIVERED, READ, FAILED` | `baseline:330-336` |
| The delivery service writes **`SENT`** (`markSent`) and **`FAILED`** (`markFailed`) only | `OutboundDeliveryRepository.java:65,84,147,154` |
| Meta `statuses` payloads — the callbacks that would produce `DELIVERED`/`READ` — are **verified and then discarded**: *"A `statuses` payload, or a metadata echo. Neither is a turn."* | `MetaWebhookParser.java:21-23,61-84` |
| So `DELIVERED`/`READ` are **reachable in the enum and unreachable from the delivery path**. ⚠️ The traceability row also says their only caller is a test; **I did not re-verify that half** (see §4.6). | as above |
| `NotificationType` has five members — `CONVERSATION_ASSIGNED, CONVERSATION_ESCALATED, CONVERSATION_UNASSIGNED_QUEUE, MENTION, SYSTEM` — and **none is a delivery failure** | `baseline:353-359` |
| The `notifications` table is an **in-app user notification** store (`userId uuid NOT NULL`, `readAt`), not a patient reminder store. There is **no patient-facing notification table anywhere**. | `baseline:1142-1154` |

### 4.5 What does not exist at all

| Claim | Evidence |
|---|---|
| **None** of `reminder_policies`, `reminder_attempts`, `patient_consents`, `waitlist_entries` exists — `CREATE TABLE` census across all 27 files in `ops/flyway/sql/` | verified here |
| **There is no scheduler in the codebase.** `@Scheduled`, `@Cron` and `Quartz` return zero production hits. | `requirements-traceability.md:74-75` |
| **There is no reminder queue** in the closed `QueueName` enum | `QueueName.java:26-48` |
| **Inbound idempotency is genuinely strong** — three layers: provider-event reservation, outbox `idempotencyKey` uniqueness, and `CREATE UNIQUE INDEX messages_projectId_channel_externalMessageId_key` | `baseline:2279`; `V20261004000100:286` |
| …so **inbound** duplicate suppression is solved. **Outbound** reminder deduplication is not: no table, no key, no rule. | as above |

### 4.6 Not verified

No live database was inspected; every schema claim is read from the migration corpus. The
`requirements-traceability.md` rows are quoted from a verification performed on 2026-10-12 at commit
`f182ada` and were **not** re-run here. I have not contacted the clinic or its provider accounts, so
**I do not know whether this clinic has a WhatsApp Business account with approved templates** — and
that single fact decides whether option S-1 below is viable at all.

---

## 5. Options

### 5.1 Channels

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **S1 — WhatsApp only** | One production channel; `MOCK` in dev | Smallest operational surface; the 24-hour window and template approval become the **only** outbound problem, and they are **solved or not at all** by the provider account | SMS and email. For a clinic that later wants a channel with no provider approval gate, this is additive work, not a rework |
| **S2 — WhatsApp + one fallback** (SMS or email) | Two channels with different properties: one conversational, one broadcast | **Requires building a connector that does not exist** (`ChannelType` has no SMS/email), plus its own consent and delivery semantics | Nothing |
| **S3 — The five channels that exist** | WhatsApp, Instagram, Messenger, Telegram, plus Mock | No new connector; but Instagram and Telegram are **not in the clinic's own list**, so this is the source's question answered with the platform's inventory rather than the clinic's need | A clinic that only wants WhatsApp is handed four channels it must then explicitly refuse |
| **S4 — Per-clinic channel set, defaulting to none** | Each clinic declares its approved channels; an unconfigured clinic sends nothing | Configuration UI and per-channel consent; matches DEC-013's fail-closed shape | Nothing |

### 5.2 Reminder cadence and sending hours

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **R1 — One clinic-wide cadence and window** | e.g. 24 h and 2 h before, 09:00–20:00 local | Simplest; one policy row per clinic | Per-service or per-appointment-type cadences — additive later |
| **R2 — Per clinic, per service, per channel** | The full matrix | The most configuration surface of the four, and every value is a number nobody has supplied | Nothing |
| **R3 — Per clinic cadence; window derived from the clinic's working hours** | The sending window is the clinic's own opening hours plus an explicit override | Fewer numbers for the clinic to supply, but it silently couples communication to DEC-001's `doctor_schedules` — which is **not** answered | Clinics that want to message outside opening hours |
| **R4 — Cadence expressed *relative to duration*** (e.g. "48 h before", scaled for a 90-minute surgery) | Reminder offsets are typed against DEC-001's interval | Requires DEC-001 first (§8.1) | Nothing, but it is a dependency, not a choice |

### 5.3 Consent

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **T1 — Opt-out model**: messages may be sent unless the patient stops them | The lightest thing that can be built; needs a stop list | ⚠️ In many jurisdictions this is **not** lawful for a business contacting a consumer; it is a legal question, not an engineering one | A clinic in a consent-required jurisdiction |
| **T2 — Opt-in model**: a patient is messaged only after an explicit agreement, per channel, with a withdrawal timestamp | The shape `patient_consents` was named for | Needs a consent capture surface; existing conversations in other channels are not automatically covered | Nothing |
| **T3 — Opt-in, with the clinic deciding per channel** | Consent is per `(patient, channel)`, so a patient may accept WhatsApp and refuse email | One extra key; a slightly more complex consent screen | Nothing |

---

## 6. Recommendation — **engineering recommendation, requires clinic and legal sign-off**

**Build the consent and attempt records as a superset; ship with no reminder sending at all until DEC-005
is answered; and treat the consent check as a gate in the outbound path, not as a feature of the
reminder service.**

1. **A consent check belongs in `OutboundGuard`, not in the clinic reminder service.** `OutboundGuard`
   is the single pre-flight every outbound send already passes (`OutboundGuard.java:32-60`), and its
   three existing checks are exactly the shape a consent check should take: *ask the channel before
   acting rather than assuming parity* (`:12`), and return the reason rather than throwing so the
   caller can record it and dead-letter the job instead of surfacing a 500 to an employee who has
   already pressed Send (`:21-24`). A consent check that lives only in the reminder service is a check
   that every other sender bypasses — and every other sender exists.
2. **Model consent as an append-only event with `withdrawnAt`, never a boolean.** Same reasoning as
   DEC-010's consent model: a boolean makes *withdrawal* unrepresentable and lets a later consent be
   written over an earlier refusal. The clinic will need that record in a dispute, and it must never be
   deletable.
3. **`reminder_attempts` is one row per attempt, not per reminder.** The source asks for sent / delivered
   / failed (`source` §4.3), the Phase 6 gate asks for deduplication and visible delivery state
   (`plan.md:216-217,222`), and a retry is a distinct event. One row per reminder cannot represent a
   second attempt, and the outbox retry/dead-letter machinery already thinks in those terms.
4. **Make the deduplication key a column, not an application check.** The inbound side already proves
   the pattern works (`baseline:2279`, `V20261004000100:286`). A reminder's natural key is
   `(appointmentId, policyId, scheduledFor)` — a `UNIQUE` index makes "duplicate events do not create
   duplicate reminders" (`plan.md:222`) a database property rather than a code path that can be
   forgotten.
5. **Do not bake the sending window into the send path as a clock comparison.** It belongs in
   `reminder_policies` as a stored window evaluated against the clinic's timezone, because DEC-008 makes
   the clinic a `Project` and `Project.timezone` already exists. A window written as server-local time
   is a window that is wrong for every clinic outside the server's zone.
6. **Do not send anything before this decision is answered.** The register's rule is that an open
   decision keeps its dependent capability disabled (`open-decisions.md:6`), and DEC-013 makes AI
   fail-closed for the same reason. "No consent record ⇒ no send" is the fail-closed shape and it is
   the only one that is safe to ship before the clinic answers.
7. **Do not build SMS or email in this phase.** If the clinic names a channel the platform does not
   have, that is connector work with its own delivery and consent semantics, and it should be its own
   decision rather than an assumption inside DEC-005's answer.

**What the clinic and its legal adviser must still supply, and this brief does not supply:** the channel
set, the languages, the reminder cadence and offsets, the sending window, the opt-in/opt-out model, and
the reply-handling policy. Nothing here is an answer to any of those.

---

## 7. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Consent assumed opt-out when the jurisdiction requires opt-in | Every send is unlawful, and the evidence of what was sent is already in `reminder_attempts` | **No** — the messages left the building |
| A consent check added to the reminder service only | Every other outbound sender (AI confirmations, replies, operational messages) keeps sending without it, and the control is invisible to anyone auditing the outbound path | Yes — but the gap is open until it is closed |
| Consent modelled as a boolean | Withdrawal is unrepresentable; a re-consent can be written over an earlier refusal | **No** |
| A sending window written as server-local time | Messages land at 03:00 for a clinic eight hours east, and the clinic is the one who gets the complaint | Yes, once a timezone is bound |
| Channel set copied from `ChannelType` rather than answered | Four connectors are configured for a clinic that wants one; each is an integration with credentials, a webhook URL and a support surface | Yes, by disabling — **but only if they were never enabled**, because a connected channel accumulates history |
| Reminder cadence guessed, then reminders sent | Real messages went to real patients at times the clinic never approved. **Opting them out later does not undo having sent them** | **No** |
| `reminder_attempts` modelled as one row per reminder | A retry has nowhere to live; the Phase 6 gate's "duplicate events do not create duplicate reminders" becomes a code promise rather than a constraint | **Yes** — additive |
| Deduplication left as an application check | Duplicate reminders after a retry, a webhook redelivery, or two workers — exactly what `plan.md:222` forbids | Yes, but patients have already received two texts |
| Template support assumed because the window allows it | Reminders silently do not go out outside 24 h, and nothing reports it — the "system never pretends an unconfigured connector succeeded" rule (`PROJECT_REQUIREMENTS.md:128`) applied to a missing capability | **No** — the misses are invisible unless someone builds the reporting |
| Opt-out treated as a cancellation intent | A patient who says "stop messaging me" has an appointment cancelled by a keyword. This is **already** what the scoring does (§4.3) | **No** — the cancellation has happened |

**The asymmetry:** policy columns, consent event columns and attempt rows are additive and cheap.
**A message actually sent** is not recoverable by any migration.

---

## 8. Dependencies and conflicts

### 8.1 DEC-001 — a reminder is scheduled against a duration

Option R4 (offsets relative to duration) cannot be built before DEC-001. Even under R1, the offsets are
*relative to the appointment time*, so a multi-session procedure inherits DEC-001's multi-session answer.

### 8.2 DEC-003 — a cancellation notice is a message, and a reply is a cancellation

`source` §4.3 asks for reply handling («ردود مثل … طلب … إلغائه»), and DEC-003 owns the cancellation
policy those replies would apply. **A reply-driven cancellation is an automated consequential action**,
so it is DEC-006's approval question as much as DEC-003's policy question. Do not let one decision
close the other by implication.

### 8.3 DEC-007 — a communication record is a record

`reminder_attempts` holds patient contact facts and messages. DEC-007's per-class retention answer
(§9 B-classes) has to cover it, and an export authority that includes messages has to redact message
bodies the way DEC-007 §D requires. Recorded rather than resolved.

### 8.4 DEC-006 — the AI is a sender too

`plan.md:292` puts `send_confirmation` among the four typed AI workflows. Whatever DEC-005 answers, the
AI sends through the same connectors and therefore through the same consent gate — which is the strongest
argument for putting the check in `OutboundGuard` (§6.1) rather than in a service.

### 8.5 DEC-008 / ADR-0018 — configuration lands on the project

There is no `ClinicLocation`, so `reminder_policies` is **project-scoped**, and the sending window is
evaluated against `Project.timezone`. A clinic with a branch in a second timezone has no home for a
per-branch window — recorded in §10; I do not own `plan.md` or DEC-008.

### 8.6 ⚠️ Conflict: the source names channels that do not exist, and the platform has channels the source does not name

The clinic's draft asks about **WhatsApp, SMS, call, email**. The platform ships **WhatsApp, Instagram,
Messenger, Telegram**. This is not a detail to smooth over: answering DEC-005 by copying `ChannelType`
would hand the clinic two channels it never asked for and omit two it did.

### 8.7 The blocking engineering change (§2) is not owned by this decision

The outbox not-before API and the `QueueName` addition are prerequisites for Phase 6 **whatever the
clinic answers**. Recorded in §10.

---

## 9. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| `reminder_policies` columns | **Yes** | Additive, nullable |
| `reminder_attempts` rows | **No** once written | They are the evidence that a message was or was not sent |
| Consent event rows | **No** | Append-only evidence; DEC-007 §5 exempts history from purge by construction |
| Consent modelled as a boolean first, event later | **Yes for new rows, no for old** | Old rows carry no withdrawal and no version of the wording |
| Channel set as configuration | **Yes** | A configuration change — **provided no channel was ever connected** |
| A channel connection once established | Effectively **no** | Credentials, a webhook URL, message history on the provider side |
| Sending window as configuration | **Yes** | A config change |
| A **message actually sent** | **No** | It left the building |
| A **cancellation triggered by a keyword** (DEC-003) | **No** | See §7 |
| Dedup key as a `UNIQUE` index | **Yes** | Drop and recreate |
| Dedup left as an application check | Yes to fix, **no** for the duplicates already delivered | — |

---

## 10. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| 1 | Which channels, from the clinic's own list: WhatsApp, SMS, phone call, email — and which are in use **today**? | |
| 2 | Does the clinic have a WhatsApp Business account with **approved message templates**? (This decides whether option S-1 is viable; see §4.6.) | |
| 3 | Response languages — Arabic only, English only, or both? Is the patient's language taken from their profile or their message? | |
| 4 | Which appointments trigger a reminder, and at what offsets before the appointment time? | |
| 5 | Are offsets fixed numbers, or proportional to the procedure's duration (DEC-001 R4)? | |
| 6 | **Permitted sending hours** — by clinic timezone, by channel, or per clinician shift? Are weekends and public holidays different? | |
| 7 | May a **confirmation** (after a booking is saved) be sent outside those hours, given the source says confirmations go out on successful save? | |
| 8 | Consent model: opt-in or opt-out, and is it **per channel**? | |
| 9 | What does "stop" mean in each language you use — stop all messages, stop this channel, stop reminders but keep confirmations? | |
| 10 | Where is consent captured, and does it apply to a conversation that already exists on another channel? | |
| 11 | Reply handling: may a reply **confirm**, **reschedule** or **cancel** automatically, or does it always raise a human task? (Cross-link DEC-003 and DEC-006.) | |
| 12 | Should voice notes be transcribed, refused, or passed through as an attachment? (Traceability C-4: today an audio-only message reaches a human as the literal string `[audio]`.) | |
| 13 | What happens when delivery fails — who is alerted, and by which channel (an alert by SMS about a failed SMS)? | |
| 14 | How long is the message record kept, and may a patient see their own message history? (Cross-link DEC-007.) | |

Approver, date, and whether each answer is reversible: ______________________

**Follow-up for the orchestrator (not mine to edit):**

1. **`OutboxIntent` and `OutboxRepository.ENQUEUE` need a not-before** so a reminder can be scheduled
   for T+24h. Verified: **no DDL is required** — `outbox_events."nextAttemptAt"` already exists and is
   already the claim predicate (`V20261004000100:240`; `OutboxRepository.java:53,78`). It is a Java API
   change plus producer propagation. Until it lands, **Phase 6 work item 2 cannot start regardless of
   DEC-005's answer.**
2. **`QueueName` needs a reminder member.** It is a closed enum by design (`QueueName.java:7-14`) with no
   such member today (`:26-48`).
3. `requirements-traceability.md` row R-5 states *"stop is scored as a cancellation intent"*. The
   substantively equivalent claim is verified, but the literal token is not what the code does: the
   `cancellation` intent pattern set is `\b(cancel|unsubscribe|close account)\b` plus the Arabic
   `(إلغاء|إيقاف الاشتراك|إغلاق الحساب)` (`packages/ai/src/scoring/evaluator.ts:82`;
   `ConversationScoring.java:88-89`). "Stop" appears only as a provider `finishReason`. The row may want
   rewording; I did not edit it.
4. Several traceability line references did not reproduce in the working tree at `75c73ae`
   (e.g. R-4 cites `OutboundDeliveryRepository.java:101`; the `SENT`/`FAILED` writes are at `:65`/`:84`
   and the claim predicate at `:107`). Cited here at the lines I verified.
5. `plan.md:128` names `patient_consents` while `plan.md:132` names `treatment_consents` — two consents,
   two similarly named tables. DEC-010 already flags this for the clinical one.

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007) and Phase 2 can specify `reminder_policies`, `reminder_attempts` and
`patient_consents` with real values.
