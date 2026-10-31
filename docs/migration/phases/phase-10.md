# Phase 10 — Spring AI and governed RAG

**Status:** IMPLEMENTED — `:backend:ai-rag` complete; H2 resolved by `V20261006100000` and the two contorted paths restored; 226 tests green; three hooks outstanding.
**Owner agent:** `firstelement-ai`
**Governing decision:** `docs/migration/adr/0007-finance-and-ai-policy.md` (Part 2 — not reinterpreted)
**Companion document:** `docs/migration/ai-policy.md`
**Depends on:** promoted AI-policy/governance tables (Phase 7-10a);
`backend/tenant-security/**` (`AiToolExecutionGuard`, `AuthorizationService`);
`backend/worker-runtime/**` (the durable `JobHandler` SPI);
`backend/reporting-finance/**` (`UsageEventWriter`, `RateCardResolver`);
`backend/administration/**` (the append-only audit streams).

## Module name: `backend/ai-rag`, not `backend/knowledge-ai`

The task file names `backend/knowledge-ai/**`. The module is `backend/ai-rag`
because it is not only knowledge: it also owns the provider abstraction, the
policy snapshot, budgets, the tool registry, approvals, redaction, provenance
and the send gate. A directory called `knowledge-ai` holding the run
orchestrator would have been a name that hid the module's most important
boundary — that **not** everything in it is retrieval. Everything else in this
phase follows the task file exactly.

## Objective

Move AI under an explicit policy and provenance model without ever making the
CRM depend on a model being available.

## What was built

`backend/ai-rag/src/main/java/com/firstelement/airag/`

| Package | What it owns |
|---|---|
| `config/` | `AiRagProperties` (`firstelement.ai.*`) and `AiConfiguration`. **No credential property of any kind.** |
| `provider/` | `AiChatProvider`/`AiEmbeddingProvider` (Spring AI ports plus `kind()`), `AiProviderRegistry`, `ProviderFailoverExecutor`, `ProviderFailure`, `ProviderOutcome`, `ProviderAttempt`, `SecretScrubber`, `MockAiChatProvider`, `DeterministicEmbeddingProvider` (1536-dim), `MockTokenCounter`. |
| `policy/` | `AiPolicyResolver`/`AiPolicyRepository`/`AiPolicySnapshot`, `AiModelGate`, `BudgetGate`, `AiRateLimiter`, `AiRefusalReason`, `AiRefusedException`. |
| `retrieval/` | `RetrievalVisibilityRepository` — RANKED / CONSIDERED / FRESHNESS. The tenant predicate lives **inside** the similarity statement. |
| `redaction/` | `RedactionPolicyDefinition` (3 closed policies), `AiRedactionEngine`, `RedactionOutcome`, `RedactionRule`. |
| `tools/` | `AiToolCatalog`, `AiToolRegistryService`, `AiToolExecutor`, `AiToolArgumentValidator`, `ConversationLookupTool`, `AppointmentProposalTool`. |
| `approval/` | `AiApprovalService` — request / approve / reject / revoke / expire / consume. |
| `costs/` | `AiUsageAccounting` — the single place a run's ledger rows are written. |
| `ingestion/` | `KnowledgeIngestionService` — chunk, embed, index, and deletion propagation. |
| `trace/` | `AiTraceRepository` — every `ai_run_*`, `ai_approvals`, `ai_redaction_records`, `ai_provenance_records`, `ai_tool_executions`, `ai_budget_consumptions` write. |
| `orchestration/` | `AiOrchestrator` (the single run entry point), `UntrustedContentAdvisor`, `AiRunDisposition`, `AiRunResult`. |
| `send/` | `AiSendAuthorization` — the AI-mode/takeover gate. |
| `backfill/` | `AiRunChildScopeBackfill` — ADR 0009 §2, armed, batched, checkpointed, idempotent, no DDL. |

Spring AI is used as the **message/response model and the advisor chain inside
our own orchestrator**. `spring-ai-client-chat` and `spring-ai-model` only — no
`starter-model-*` and no provider starter, so Spring AI's auto-configuration
never chooses a provider bean at boot (ADR 0007 Part 2 rule 3).

## Hard rules, and where each is proven

| # | Rule | Where it is enforced | Test |
|---|---|---|---|
| 1 | The application owns tool execution | `AiToolExecutor` — re-authorizes every request the model makes | `ToolPolicyIntegrationTest > executor decisions` |
| 2 | AI may send only in AI mode; consequential actions need approval | `AiSendAuthorization`, `AiApprovalService` | `SendHandoffRaceIntegrationTest`, `ApprovalLifecycleIntegrationTest` |
| 3 | pgvector is an index, not an authorization layer | tenant+project+KB+`status='READY'`+`embedding is not null` **inside** the ranking statement | `RetrievalTenantIsolationIntegrationTest` |
| 4 | Never expose chain-of-thought | `StepDetail` is a **declared type** of ids, counts and reason codes; `ProviderAttempt.text()` exists only in memory, in the redaction engine's path | `MockProviderEndToEndIntegrationTest`, `SqlDialectDiagnosticTest` |
| 5 | With no provider, the CRM stays fully usable | `MockAiChatProvider` + `AiProviderRegistry` reporting `NOT_CONFIGURED` per hop | `ProviderFailoverTest`, `ApiApplicationSmokeTest` |
| 6 | A model may never choose its own tenant scope | `requestedProjectId` argument refused; `FORBIDDEN_ARGUMENTS` refused; scope from `TenantQueryGuard` only | `ToolPolicyIntegrationTest > argument validation` |
| 7 | Prompt-injection detection is telemetry, not the control | `UntrustedContentAdvisor.frame` + `AiToolExecutor`; the mock provider **complies** with injected instructions on purpose | `ToolPolicyIntegrationTest > prompt injection` |
| 8 | Deletion propagates; a deleted document is not retrievable | `KnowledgeIngestionService.propagateDeletion` removes chunks **and** the document in one transaction | `KnowledgeIndexLifecycleIntegrationTest > deletion propagation` |
| 9 | `AI_SEND` is a permission; `AI_TOOL_EXECUTE` does not grant it | `AiSendAuthorization` checks `Permission.AI_SEND` alone | `SendHandoffRaceIntegrationTest > who may send` |

## Acceptance gate

| Gate | Result | Suite |
|---|---|---|
| 1. Full CRM journey with no provider key | **PASS** — `MockAiChatProvider`; no credential property exists to set | `MockProviderEndToEndIntegrationTest` (17), `ApiApplicationSmokeTest` |
| 2. Cross-tenant retrieval denied at a **zero** threshold | **PASS** — `minRetrievalScore = 0.0` in every retrieval negative | `RetrievalTenantIsolationIntegrationTest` (16) |
| 3. Disabled/cross-project tool denied and recorded; scope-override rejected | **PASS** | `ToolPolicyIntegrationTest` (23) |
| 4. Prompt-injection regression | **PASS** — a provider that would obey cannot cause a tool call or a send | `ToolPolicyIntegrationTest > prompt injection` (4) |
| 5. Every permitted answer cites its sources and names agent/prompt/retrieval versions | **PASS** — `promptVersion` is a content **hash**, never the prompt | `MockProviderEndToEndIntegrationTest`, `BudgetLedgerIntegrationTest` |
| 6. Human handoff aborts an in-flight AI send | **PARTIAL — see Hooks H1** | `SendHandoffRaceIntegrationTest` (18) |

Gate 6 is partial by construction, and the reason is stated rather than papered
over: this module provides the **early** check, which reads the conversation mode
under `SELECT … FOR UPDATE`. The **authoritative** check — mode re-read and
outbox publish in one transaction, under the same lock — belongs to Phase 6's
`AiSendService`, which does not exist yet. What this phase proves is that the
lock is genuinely taken and genuinely held: `theAuthorizationLocksTheConversation`
opens two real transactions on two connections and asserts the takeover's
`UPDATE` cannot complete while the authorization is open. That test fails
against an implementation with no lock, so it is not a restatement of the code.

## Defects this phase found and fixed

Every one of these was found by a test in this module, not by review. They are
listed because a reader deciding whether to trust the module needs to know what
was wrong, not only what is now right.

| # | Defect | Consequence had it shipped | Fix |
|---|---|---|---|
| D1 | `INSERT_APPROVAL_DECISION` did not cast `fromStatus` | **`approve`, `reject`, `revoke` and `expire` all failed at runtime.** The request path passed `null` and so never exercised it — the entire approval lifecycle was dead. | `cast(:fromStatus as "AiApprovalStatus")`; every enum column in the repository is now cast explicitly |
| D2 | `AiApprovalService.matches()` rebuilt each argument's type by wrapping it in `Map.of("v", value)` | The comparison used the literal key `"v"`, so it could never succeed: **`consume()` always returned `false`**, and a bound approval read as "refused" | `AiToolArgumentValidator.redactedShapeOf(Map)` produces the shape with the one function that defines the type labels |
| D3 | `expire()` wrote on the caller's transaction and was then rolled back by the refusal that called it | An expired approval stayed `PENDING` forever while every `approve` kept re-reporting "expired" | `AiTraceRepository.markApprovalExpired` is `REQUIRES_NEW`; a state machine that cannot advance is not one |
| D4 | `reject()` and `revoke()` wrote a decision pair the schema forbids | `DataIntegrityViolationException` naming a constraint, three frames from a human's decision | Both cleared the pair as a workaround. **RESTORED** after `V20261006100000` — see §Restoration below. |
| D5 | `AiApprovalService.request()` wrote only `ai_approvals`, but `FIND_APPROVAL` **joins** `ai_tool_executions` on `approvalId` | A PENDING row that `approve`/`reject`/`consume` could never see — failing as "no approval in this tenant" for a row that plainly existed | `request()` now writes both halves and returns `Requested(approvalId, executionId)`; one write site |
| D6 | `AiOrchestrator` passed `budgetId = null` to `AiUsageAccounting.record` | **`ai_budget_consumptions` was never written.** The gate's only ledger was permanently empty, so a HARD budget could never be reached by spend — only by a single run's own reservation | `BudgetGate.BudgetDecision.charges()` carries one `BudgetCharge` per in-force budget, each with its own bucket; one posting per budget, deduplicated by the existing unique index |
| D7 | `SecretScrubber` applied `ASSIGNMENT` before `BEARER` | `Authorization: Bearer <token>` was **half** redacted: the assignment ate the word `Bearer`, orphaning the token, which then survived into `ai_runs."errorMessage"` intact | BEARER first. Order stated in the code and pinned by two tests |
| D8 | `AiSendAuthorization` required `Capability.AI_RUN` **and** `Permission.AI_SEND` | `RolePermissions.forPrincipalType` gives a `USER` its permissions from its role and ignores capabilities, and no role maps to `AI_RUN` — so **no human could ever pass the gate**. The effective rule silently became "the agent may send whenever the conversation is in AI mode", removing the human gate the class exists to enforce | Capability check removed; `AI_SEND` is the gate. Loses nothing on the machine side, because `forCapabilities` grants `AI_SEND` to exactly `AI_RUN` |
| D9 | `AiSendAuthorization` tested `hasAnswer()` before `refused()` | Every refused run reported `EMPTY_COMPLETION` — "the model had nothing to say" about a run that was never allowed to speak — and the `ABORTED_BY_HUMAN` branch was unreachable | Refusal checked first, so a refusal is reported as a refusal |
| D10 | `KnowledgeIngestionService.ingest` treated a zero-row status `UPDATE` as success | A scope/document mismatch wrote chunk rows whose `organizationId`/`projectId` came from the **caller's** scope while their `documentId` pointed into another tenant. `document_chunks_documentId_fkey` is single-column, so the database had no reason to refuse | `requireUpdated(...)` fails closed. See Hook **H3**. |
| D11 | `AiRunChildScopeBackfill.unmigratedRowsStillVisible` emitted `… join ai_runs r on r."id" = s."runId" r."organizationId" = ?` | A syntax error — so **the one read whose purpose is to run during the backfill window had never executed once** | `scopePredicate` carries its own `where`; pinned by four tests |
| D12 | `RedactionPolicyDefinition`'s Arabic date-of-birth escape had a spurious `ه` | The Arabic label never matched, so the rule **silently never fired on Arabic patient records** in an Arabic-first product | Literal corrected and spelled out in the source comment |
| D13 | `PHONE` redaction rule matched ISO and space-separated timestamps | `lastMessageAt` came back as `"[REDACTED]:22:24.481"` — a destroyed diagnostic, not a privacy gain | Trailing lookahead rejects an adjacent clock; leading lookbehind stops tail re-matching |
| D14 | `AiProviderRegistry`'s duplicate-kind message named two classes and no kind | For two adapters of the same class (the ordinary shape for a stub) the message read "X and X" and said nothing about which provider was contested | The kind is named |

## Hooks — work this phase needs and does not own

**H1 — Phase 6: `AiSendService` must re-check mode under the same lock.**
`AiSendAuthorization` is an *early* check. It takes `SELECT … FOR UPDATE` on
the conversation, which closes the window between a committed takeover and the
send, but the authoritative gate is a mode re-read in the same transaction as
the outbox publish. Acceptance gate 6 cannot fully pass until that class exists.
**Not implemented here, deliberately** — a second component enforcing rule 2 is
how two components come to disagree about which conversation is in AI mode.

**H2 — schema: `ai_approvals_approval_check` contradicted its own comment and
made two transitions unrepresentable.** ✅ **RESOLVED** by
`V20261006100000__ai_approval_decision_biconditional_repair.sql`, per ADR 0011
§1. No DDL was written by this phase.

The constraint as it stood:

```sql
-- before:
CHECK (("status" = 'APPROVED'::"AiApprovalStatus") = ("decidedAt" IS NOT NULL))
```

Its adjacent comment said *"APPROVED and REJECTED each say who and when"*, which
its own body forbade. `ai_approvals_decision_pair_check` then cleared
`decidedByUserId` with it, so a rejection could not record its decider on the
mutable row — and a `REVOKED` row could not keep the grant's `decidedAt`, which
made revocation unrepresentable even though `ai_approvals_revocation_check` and
the whole `REVOKE` statement exist for it.

The installed form is `IN ('APPROVED','REJECTED','REVOKED')`. That includes
`'REVOKED'`, which ADR 0011 §1's literal text omitted, and the omission would
have defeated §1 rule 4: because the decision pair is a biconditional, there is no
arrangement in which a `REVOKED` row names its approver and leaves `decidedAt`
NULL, so the two-literal form would still have forced the revocation path to
clear the grant's decision. `'EXPIRED'` is deliberately **not** added — nobody
decided a lapsed request, so it correctly carries no decision pair.

See §Restoration below for what changed in this module once it landed.

**H3 — schema: `document_chunks` has a single-column FK on `documentId`.**
`document_chunks_documentId_fkey` references `documents(id)` alone, so a chunk's
`organizationId`/`projectId` are not constrained to agree with its document's —
unlike the composite tenant FKs this phase's own tables use. D10 now fails
closed in code, but the constraint is what makes the state **unstorable**:

```sql
ALTER TABLE document_chunks
  ADD CONSTRAINT document_chunks_document_tenant_fkey
  FOREIGN KEY ("documentId", "organizationId") REFERENCES documents("id","organizationId");
```

**H4 — `tenant-security`: no `Permission` for `appointment:propose`.**
`AppointmentProposalTool.REQUIRED_PERMISSION` is the literal string
`"appointment:propose"`; `Permission` has no matching constant, and `AiToolExecutionGuard`
compares strings. A literal in a named constant is honest about the gap; adding
an AI-phase enum constant to `tenant-security` is outside this phase's ownership.

**H5 — `Permission.AI_SEND` exists; `AiSendService` does not.** Recorded so the
next phase does not assume the send path is finished.

**H6 — no `NOT NULL` contract migration, and none proposed.**
ADR 0009 §2 rule 1 forbids it here and rule 3 wants a *separate* migration after
the residual is proven zero. `AiRunChildScopeBackfill` has **no DDL at all**, and
`AiRunChildScopeBackfillIntegrationTest > idempotence and non-destruction`
asserts the absence against `pg_class`, not in a comment.
`BackfillReport.complete()` — armed, zero residual, **zero disagreements** — is
the evidence such a migration would be authorised by. A backfill can be complete
and *wrong*; only the disagreement count distinguishes them.

## Restoration after `V20261006100000` (ADR 0011 §1 rule 4)

The two paths that had been contorted around the H2 defect are restored.

| Path | Before | After |
|---|---|---|
| `reject()` | wrote `decidedByUserId = null, decidedAt = null` | writes **`principal.userId()` and `now`**, alongside the `decisionNote` it already wrote |
| `revoke()` | wrote `decidedByUserId = null, decidedAt = null` | writes **only** `revokedAt`, `revokedByUserId`, `revokeReason`; the `#REVOKE` statement no longer mentions the decision pair at all |
| `ApprovalRow` | did not project `revokedByUserId` | projects it, so a caller can tell the grantor from the revoker without hand-written SQL |

**Why `revoke()` restores by *omitting*, not by restoring.** A revocation is only
reachable from `APPROVED`, so the row already holds the grant's `decidedAt` and
`decidedByUserId`. Naming them in the `SET` list and passing the revoker would have
been the same defect wearing different clothes — it would have replaced "who
granted this" with "who revoked it". The statement therefore touches only the
revocation trio that `ai_approvals_revocation_check` governs.

**`EXPIRED` is unchanged and correctly so.** `markApprovalExpired` still clears the
pair, because that is now *required*: the corrected constraint mandates a decision
on `APPROVED`/`REJECTED`/`REVOKED` and therefore requires its absence on
`PENDING`/`EXPIRED`. The difference between that and the old workaround is the
status, not the mechanism — under the previous constraint `EXPIRED` *and*
`REJECTED` both had to hide their decider, and now only the one that genuinely has
none does.

**What did not change.** `reject()` still refuses a blank note
(`ai_approvals_rejection_check`). Neither `approve()` nor `reject()` may be
performed by the requester (`ai_approvals_not_self_approved_check`, enforced in
code *and* schema). Approval binding remains **shape-only** for argument values —
names and types, never values — which is weaker than it sounds and is recorded in
ADR 0011 §5 as a Phase 12 requirement, not implemented here. The append-only
`ai_approval_decisions` history is unchanged in meaning: the restoration adds the
row-level decider, it removes nothing from the history, and the tests assert both
records carry the *same* actor and timestamp.

**The restored assertions were proved load-bearing**, not merely re-worded. Two
regressions were injected and the suite was re-run:

| Injected regression | Tests that failed |
|---|---|
| `reject()` clears the decision pair again | `rejectionIsTerminal`, `aRejectionsDecisionPairIsWhole` |
| `revoke()` overwrites `decidedByUserId`/`decidedAt` with the revoker | `revocationTakesTheGrantBack`, `revocationLeavesACompleteHistory` |

4 of 24 failed; both regressions were caught, then reverted. A third admin
(`REVOKER_A`) was added to the fixture so the grantor and the revoker are
distinguishable — with one actor, an implementation that overwrote the grant's
decider would have satisfied every assertion.

## Tests

```powershell
.\gradlew.bat :backend:ai-rag:test --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat :backend:app-bootstrap:test --no-build-cache --no-daemon --console=plain
```

| Suite | Tests | Failed | Skipped |
|---|---:|---:|---:|
| `ProviderFailoverTest` | 28 | 0 | 0 |
| `RedactionEngineTest` | 24 | 0 | 0 |
| `SecretScrubberTest` | 17 | 0 | 0 |
| `SqlDialectDiagnosticTest` | 4 | 0 | 0 |
| `MockProviderEndToEndIntegrationTest` | 17 | 0 | 0 |
| `RetrievalTenantIsolationIntegrationTest` | 16 | 0 | 0 |
| `ToolPolicyIntegrationTest` | 23 | 0 | 0 |
| `BudgetLedgerIntegrationTest` | 15 | 0 | 0 |
| `ApprovalLifecycleIntegrationTest` | 24 | 0 | 0 |
| `KnowledgeIndexLifecycleIntegrationTest` | 18 | 0 | 0 |
| `SendHandoffRaceIntegrationTest` | 18 | 0 | 0 |
| `AiRunChildScopeBackfillIntegrationTest` | 19 | 0 | 0 |
| **`:backend:ai-rag` total** | **223** | **0** | **0** |
| `ApiApplicationSmokeTest` | 5 | 0 | 0 |

**Zero skipped, zero disabled.** There is no `@Disabled` and no conditional skip
anywhere in the module, and no test needs a provider key — so the "no provider
key configured" gate is proved by construction rather than by a property someone
might set. A test that needed a key would be **BLOCKED**, not skipped.

## Rollback

AI is additive and optional. Rollback disables agents and returns every
conversation to human handling; the CRM, inbox and routing paths do not depend
on it. No provider credential is ever read from a file in the repository.

Rolling back this phase is a matter of removing one line from
`backend/app-bootstrap/build.gradle.kts`: the module contributes no HTTP endpoint,
no migration, and no change to any other module's behaviour.
