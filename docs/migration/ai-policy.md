# AI policy — how a FirstElement AI run is allowed to happen

**Phase:** 10 (`backend/ai-rag`)
**Governing decision:** `docs/migration/adr/0007-finance-and-ai-policy.md` Part 2
**Enforced by:** `AiOrchestrator` and the classes it calls, in the order below.

This document exists because the policy is otherwise only discoverable by reading
Java. Everything here is enforced in code and asserted by a test; the test is
named so a reader can check the claim rather than trust it.

---

## 1. The one rule everything else serves

**A model may request. The application decides.**

Nothing in this module asks a model whether it may do something. Every capability
a run has — reading knowledge, calling a tool, spending budget, sending to a
customer — is checked by application code against a resolved `TenantContext`, a
`TenantScope` and a policy snapshot, **before** the capability is exercised. A
model's agreement to a constraint is not authorisation; it is a request, and it is
recorded as one.

Concretely: `AiToolExecutor` re-authorizes every tool call the provider asked
for, even though the orchestrator already chose which tools the agent version
declares. The second check is not redundancy — it is the only check that sees the
*arguments* the model actually produced.

---

## 2. The order of a run, and why each step precedes the next

`AiOrchestrator.execute` is the single entry point. The order is the policy.

| # | Step | Why it cannot come later |
|---|---|---|
| 1 | **Rate limit** (`AiRateLimiter`) | Before anything reads a row. Stops a runaway conversation. |
| 2 | **Resolve the agent and its pinned version** | By id, never "the active version" — a run must stay attributable to the version that produced it (FR-5.3). |
| 3 | **Resolve the policy** | No policy, or no `APPROVED` version → refused. |
| 4 | **Resolve the model** (`AiModelGate`) | Against the allow-list **and** the residency zone. |
| 5 | **Resolve the redaction policy** | Before the budget, so a misconfigured policy name is not discovered *after* tokens have been estimated. |
| 6 | **Budget** (`BudgetGate`) | Token, cost and wall-clock ceilings, on the estimate, **before the call**. Refusing is free before the call and expensive after it. |
| 7 | **Retrieve** | Authorization is complete by now. No customer content is read before steps 1–6. |
| 8 | **Redact the prompt, then frame it** | Retrieved content is untrusted input and is redacted *before* it can influence anything. |
| 9 | **Generate** (`ProviderFailoverExecutor`) | With a per-attempt timeout and a bounded hop count. |
| 10 | **Tool calls** (`AiToolExecutor`) | Each re-authorized. Nothing acts on the model's say-so. |
| 11 | **Redact the response** | A redaction failure is a refusal, not a pass. |
| 12 | **Record** | Steps, retrievals, tool calls, provenance, usage. |

**Step 6 before step 9 is the load-bearing one.** A budget that is checked
afterwards has already been paid for.

---

## 3. Providers

### 3.1 There is no credential property, and that is the design

`AiRagProperties` has no `apiKey`, no `token`, no `endpoint`, and no map of
per-provider secrets. Two reasons:

- ADR 0007 Part 2 rule 3 — *with no provider key the CRM stays fully usable*. A
  key that can be configured is a key someone will put in `application.yml`, and
  this migration does not read a credential from a file in the repository.
- `V20261005100000` deliberately gives `ai_model_registry` **no credential
  column**, and fails the migration if one appears. A properties class is the
  same mistake in a different file.

A real provider adapter arrives in a later phase as a bean that is conditional on
a credential supplied by the deployment.

### 3.2 The mock provider is a real provider, not a stub

`MockAiChatProvider` is deterministic: same input, same output, same token
counts, no clock, no network, no randomness. Determinism is the contract, because
a budget that behaves differently on a developer's laptop is a budget nobody
trusts.

It is also built to **comply with an instruction it finds inside the framed
untrusted block**. That is deliberate inversion: it means the prompt-injection
test proves the *application* stopped the instruction, not that the mock was
helpful. A real provider might well follow it.

### 3.3 A provider outage produces a reason, not a hang

`ProviderFailoverExecutor` submits each attempt to a bounded pool of four named
daemon threads and waits with `Future.get(timeout)`. On expiry the future is
cancelled and the attempt is recorded as `TIMEOUT`.

Three outcomes that must never be confused:

| Outcome | Refusal code | Attempts recorded |
|---|---|---|
| The agent named no providers | `NO_PROVIDER_CHAIN` | none — nothing was tried |
| No adapter exists for a kind (the no-key state) | `NOT_CONFIGURED` | one, with no call made |
| The chain was walked and every hop failed | the **last** `ProviderFailure` | all of them |

**Failover rules.** `TIMEOUT`, `UNAVAILABLE`, `RATE_LIMITED` and `NOT_CONFIGURED`
move to the next provider. `INVALID_REQUEST` and `CONTENT_FILTERED` **stop** the
chain: the next provider would fail identically, and continuing would spend the
run's remaining hops and dilute the "provider is degraded" signal an operator
watches. An unclassifiable exception is `UNKNOWN`, which is not retryable.

**An empty completion is a failure.** A provider that answers with nothing has
failed; the module must never convert "the provider failed" into "the model had
nothing to say".

**An agent version naming no provider is refused**, not defaulted. The default
lives in the agent version; silently substituting one would make the model that
produced an answer differ from the model the tenant registered.

---

## 4. Retrieval

`RetrievalVisibilityRepository` puts **every** visibility predicate **inside** the
similarity statement:

```sql
where c."organizationId" = :org
  and c."projectId"      = :project
  and c."knowledgeBaseId" in (:bases)
  and d."status" = cast('READY' as "DocumentStatus")
  and c."embedding" is not null
order by c."embedding" <=> cast(:vector as vector)
```

pgvector is an index, not an authorization layer (ADR 0007 rule 3). A similarity
threshold can only make the result set **smaller**; no value of
`minRetrievalScore` can widen one. Every cross-tenant retrieval negative runs at
`minRetrievalScore = 0.0`, not at a realistic value.

Three states are deliberately distinguishable, because they are different
conversations with an administrator:

| State | Refusal code |
|---|---|
| Nothing indexed at all | `RETRIEVAL_EMPTY` |
| Indexed, but everything below the threshold | `BELOW_MIN_SCORE` |
| Indexed and above the threshold | answered, with citations |

Only the agent version's named knowledge bases are searched. A retrieval that
searched the tenant's whole corpus would be a tenant-wide search no
administrator approved.

---

## 5. Tools

### 5.1 Deny by default

`ai_tool_registry."isEnabled"` is `NOT NULL DEFAULT false`. A newly registered
tool is inert by construction. The schema makes "a disabled tool is recorded as
disabled" a **biconditional**: `isEnabled = false` requires `disabledAt`,
`disabledByUserId` and `disabledReason`, so *why is this tool off?* is always
answerable. `ai_tool_executions` records **every** tool request, allowed or
refused — a tool the model tried to call while disabled is a row, not an absence.

### 5.2 The gate order in `AiToolExecutor`

1. Unknown tool name → `DENY` / `UNKNOWN_TOOL`. (`FORBIDDEN` is reserved for a
   tool whose class is unconditionally forbidden.)
2. Out-of-scope project → `DENY` / `SCOPE_OVERRIDE_ATTEMPT`, before the body.
3. Disabled in the registry → `DENY` / the registry's **own** reason.
4. Missing capability → refused.
5. Consequential → `REQUIRE_APPROVAL`, and **nothing runs**.
6. Tool-call ceiling exhausted → refused, counting refusals as spent.
7. Only then: redact, project through `fieldAllowList`, execute.

### 5.3 A consequential tool can only ever be proposed

`AppointmentProposalTool.perform` **always throws**. It does not read an
availability table, does not write a row, and has no code path that could. The
only way a consequential action happens in this system is a human approving it
and Phase 12 performing it.

The dry run is performed by the **approver's** execution, not by the model's:
describing an unapproved action to the model would let it learn what the tool
would do and iterate on it, which is exactly the reconnaissance a consequential
tool should not enable.

### 5.4 Arguments

- The model may not supply a project or tenant. `requestedProjectId` is refused,
  and so is every name in `AiToolDescriptor.FORBIDDEN_ARGUMENTS`, whatever the
  tool.
- An undeclared argument is refused, and the message names what *is* declared.
- Values are converted to their declared types before anything reads them.
- **What is persisted is the shape — names and types — never the values.** A
  model's arguments are untrusted input and may contain a customer's details
  quoted from a document.

---

## 6. Approvals

The AI proposes; a human disposes. Three operations, and no fourth.

| State | Transition | Required |
|---|---|---|
| `PENDING` | → `APPROVED` | a different human, before `expiresAt` |
| `PENDING` | → `REJECTED` | a note (the schema refuses a rejection without a reason) |
| `APPROVED` | → `REVOKED` | a reason ("why was this permission taken back?") |
| `PENDING` | → `EXPIRED` | nobody — it simply ran out |

- **Two records, not one.** `decidedByUserId`/`decidedAt`/`decisionNote` answer *who
  granted or refused this, and when*. `revokedByUserId`/`revokedAt`/`revokeReason`
  answer *who took it back, when, and why*. A `REVOKED` row is an approved grant that
  was later withdrawn, so it **keeps** its grant decision — the revocation adds a
  second actor, it does not overwrite the first. Both are exposed on `ApprovalRow`.
- **`EXPIRED` carries no decision pair**, and that is deliberate rather than an
  omission: nobody refused the request, it ran out, and inventing a decider would put
  a name against something no human ever looked at. `ai_approvals_approval_check`
  (corrected by `V20261006100000`) makes the pair mandatory on `APPROVED`, `REJECTED`
  and `REVOKED` and therefore requires its absence on `PENDING` and `EXPIRED`.
- **Nobody approves their own request**, and nobody self-rejects either.
- **There is no auto-execution and no timeout-triggered execution.** An expired
  approval is `EXPIRED`, not a grant.
- **An approval is bound to its run, its tool and its argument shape.** An
  approval id is exactly the thing a model can carry forward across turns.
- The **shape** binding is weaker than a value binding, and is documented as such:
  only names and types are stored. Phase 12, when a tool performs, must record and
  compare the approved values.

---

## 7. Redaction

### 7.1 Three policies, closed list

| Policy | Covers |
|---|---|
| `STRICT_CLINICAL` (default) | everything `CLINICAL` does, plus credentials and payment data |
| `CLINICAL` | national identifiers, contact details, dates of birth |
| `LIGHT` | credentials only |

`STRICT_CLINICAL` is the union of the other two, so a tenant moving to it loses
no rule. The two narrow policies are **alternatives, not a ladder** — a ladder
would make the "reviewed knowledge base" escape hatch a lie.

**An unknown policy name is a refusal, not a fallback.** Falling back to `LIGHT`
would send unredacted patient data to a provider; falling back to `STRICT_CLINICAL`
would apply different rules than the approved version recorded, and the report
would name a policy that did not run.

### 7.2 Both sides, and the response side is the one people forget

Redacting the prompt only is the obvious mistake and a real one: a provider can
echo a prompt, a model can be induced to repeat one, and a retrieved snippet can
survive into an answer through paraphrase. So the response is redacted too, on
every run including a refusal's own text. **A redaction failure is a refusal** —
a run that failed redaction and recorded nothing reads as "no sensitive data was
present", which is the most dangerous false statement this system can make.

### 7.3 What is recorded

`ai_redaction_records` holds **counts and rule ids, never values**: stage, rule,
`valuesRedacted`, `charactersRedacted`. The counts are what makes "how much left
the building" answerable without keeping a copy of what left.

### 7.4 Rules are code, not configuration

A tenant chooses a **policy by name**; it can never supply a rule. A configurable
rule is a way for a prompt-injection payload to become a regex that runs over
every prompt in the deployment.

Each rule targets a **shape** and replaces the whole match with one identical
placeholder, so neither the length nor the token reveals what was removed.
Bluntness is the point: a rule clever enough to preserve the last four digits of a
national id is a rule that will eventually preserve the last four digits of one
that was formatted differently.

---

## 8. Budgets

Enforced **before** the call, on the estimate. The estimate must be *monotone* —
a bigger prompt must never estimate as smaller — or the gate can be walked around
by rephrasing a message.

| Enforcement | Behaviour |
|---|---|
| `HARD` | refuse. The run does not start. |
| `ALERT` | allow, and record the overrun as a fact. |
| `OFF` | not enforceable; still read so the gate can report the state. Still posted. |

**The effective token ceiling is the lower of the deployment's and the tenant's.**
A budget the louder party can override is not a budget.

**A budget is a `SUM` over immutable rows**, never a mutable counter. Each run
posts one `ai_budget_consumptions` row per budget in force, each carrying its own
period bucket, deduplicated by `ai_budget_consumptions_budget_run_period_key` so a
retried accounting write cannot double-charge. A run that posts nothing leaves
the gate comparing forever against its own reservation — see defect D6 in
`phase-10.md`.

**Cost comes from the rate card**, never from this module's arithmetic. The
pre-call reservation uses the model's *registered* per-1k price, which is an
attribution figure; the recorded cost is `RateCardResolver`'s.

---

## 9. Sending

`AiSendAuthorization` — four conditions, all necessary:

1. **`Permission.AI_SEND`.** Not a capability, and not implied by
   `AI_TOOL_EXECUTE`. There is no capability check alongside it, and the reason
   is worth stating: `RolePermissions.forPrincipalType` resolves a `USER`'s
   permissions from its **role** and ignores its capabilities, so requiring
   `Capability.AI_RUN` made the human path **unreachable** (defect D8).
2. **The conversation is in AI mode**, read under `SELECT … FOR UPDATE`. Outside
   AI mode the assistant may *draft*; it may not send.
3. **The run produced an answer** — and a refusal is checked *before* the answer,
   so a refused run is reported as a refusal rather than as an empty completion
   (defect D9).
4. **The disposition is answerable.** An `ESCALATED` run is waiting on a human.

**This is the early check, not the authoritative one.** It closes the window
between a committed takeover and the send by taking the row lock. The
authoritative gate re-reads the mode in the same transaction as the outbox publish
and belongs to Phase 6's `AiSendService` — see Hook H1.

There is deliberately **no fallback message**: a caller that wants one writes it
itself, with its own content, rather than this class inventing one.

---

## 10. What is never persisted, never logged

| Never | Why |
|---|---|
| Chain-of-thought | `StepDetail` is a declared type of ids, counts and reason codes. There is no field to put it in. |
| `ProviderAttempt.text()` | It exists only in memory, on the path to the redaction engine. It is never logged and never written to a trace row. |
| Retrieved content, verbatim | Snippets are redacted before they can influence anything, and the retrieved row carries ids and a score. |
| Model output verbatim | Redacted, then projected. A model can repeat anything. |
| Tool argument **values** | The shape only — names and types. |
| A provider's own message | Untrusted input that routinely embeds the request, including the header that authenticated it. Scrubbed and length-bounded by `SecretScrubber` before it can reach a log line, an audit field or `ai_runs."errorMessage"`. |
| An exception message, from an unexpected failure | The orchestrator logs the exception **class** only. A message is where a provider's echo of the prompt would end up. |
| A credential, anywhere | Scrubbing happens in `ProviderAttempt`'s own constructor, so a value that reaches it from any direction is sanitised rather than trusted. |

**Two rules that are easy to state and easy to get wrong:**

- **Never log an audit timestamp through a scrubber.** The `PHONE` rule excludes
  the date-time shape explicitly, because a redacted `lastMessageAt` is a
  diagnostic destroyed rather than a privacy gain (defect D13).
- **A cross-tenant chunk row is customer data with no owner.** Ingestion fails
  closed when the scope and the document disagree, and deletion propagation
  removes the embedding along with the chunks, because a stored vector for deleted
  content has no owner either.

---

## 11. Refusal vocabulary

Every refusal is **recorded before it is thrown**: a step carrying a reason
**code**, a finished `FAILED` run, and a provenance row with
`disposition = 'REFUSED'`. A caller then sees an `AiRefusedException` whose code
is the reason and whose message is safe. So a refusal is visible in the operator
surface even when the caller's own transaction rolls back — which is why the trace
writes are `REQUIRES_NEW`.

Reasons are named rather than free text, because "the provider failed" and "we
wrote a typo" must not be indistinguishable at 03:00.

---

## 12. Where each claim is tested

| Claim | Suite |
|---|---|
| Provider outage → reason, failover, timeout, scrubbing | `ProviderFailoverTest`, `SecretScrubberTest` |
| CRM fully usable with no key; citations; provenance; cost | `MockProviderEndToEndIntegrationTest`, `ApiApplicationSmokeTest` |
| Cross-tenant retrieval denied at a zero threshold | `RetrievalTenantIsolationIntegrationTest` |
| Deny-by-default tools, scope override, injection regression | `ToolPolicyIntegrationTest` |
| Redaction of both sides; the policies are closed | `RedactionEngineTest` |
| Budget accumulates and stops the next run | `BudgetLedgerIntegrationTest` |
| Approval lifecycle; nobody self-approves | `ApprovalLifecycleIntegrationTest` |
| Ingestion, stale index, deletion propagation | `KnowledgeIndexLifecycleIntegrationTest` |
| AI-mode gate; the takeover race; who may send | `SendHandoffRaceIntegrationTest` |
| ADR 0009 §2 backfill: armed, batched, idempotent, non-destructive | `AiRunChildScopeBackfillIntegrationTest` |
| `cast(x as y)` and never `::y` | `SqlDialectDiagnosticTest` |

**Zero skipped, zero disabled.** A test needing a provider key would be
**BLOCKED**, not skipped.
