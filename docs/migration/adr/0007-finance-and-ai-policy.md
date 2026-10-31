# ADR 0007 — Finance amount semantics and AI side-effect policy

Date: 2026-10-04
Status: **Accepted** (product decision, recorded before Phase 8 and Phase 10)

This closes the two decisions `plan.md` §9.8 and §9.9 required before
implementation. Both were stop conditions: no finance module and no AI module may
be built on a guess.

---

## Part 1 — Finance: what "billable / revenue amount" means

**Decision (product):** a usage event stores **cost**. A **versioned rate card
with an effective-dated markup** converts that cost into an internal
**billable/revenue amount**. Revenue is a derived value, never a stored input.

### Definitions

| Term | Meaning | Source of truth |
| --- | --- | --- |
| **Cost** | what the usage actually cost FirstElement (channel fees, AI tokens, storage, compute allocation) | the immutable usage event |
| **Billable/revenue** | cost × the markup on the rate-card version effective at the moment of usage | rate card version pinned on the event's rollup row |
| **Unpriced usage** | usage with **no** rate effective on the usage date | absence of a rate, shown distinctly from zero |
| **Margin** | revenue − cost, only where a rate exists | derived; never displayed where the row is unpriced |
| **Adjustment** | a reasoned, approved correction against a closed period | an adjustment record linked to source events |

### Rules

1. **Every amount drills down.** A dashboard figure resolves to the contributing
   immutable usage events *and* the rate-card version that priced them. A total
   that cannot name its rate definition is a bug, not a rounding artefact.
2. **Rates are versioned and effective-dated.** A rate card version is pinned
   onto the rollup row at computation time. Changing a rate card never mutates
   an already-computed snapshot.
3. **No mutable counters are finance truth.** A dashboard count is never
   converted into money. Finance reads the ledger, always.
4. **Closed periods are immutable.** A closed period changes only through an
   approved, audited correction that links to source events. Reopen is an
   explicit, permissioned, audited action.
5. **Zero is not "no data".** An empty period and a zero-valued period are
   distinct states in every read model.
6. **Currency and FX are explicit.** Every amount carries a currency; conversion
   records the FX source and rate date.
7. **No payment execution and no external billing.** This is an internal finance
   and usage surface. Invoices and payment credentials are explicitly out of
   scope for this migration and remain so unless a separate requirement is
   raised (`PROJECT_REQUIREMENTS` §FR-7.8).

### Rate-card ownership

Rate cards are **finance-owned configuration**, not engineering-owned. The first
release maps rate-card authoring to the administrator capability set, with the
finance/operations role as the intended daily operator. Engineering may seed a
default card but must not silently change one.

---

## Part 2 — AI: customer-visible side-effect policy

**Decision (product):** the model may send customer-visible replies **only while
the conversation is in AI mode**. Anything consequential requires an explicit
human approval step, and the application — never the model — decides.

### Action classes

| Class | Examples | Policy |
| --- | --- | --- |
| **Answerable** | knowledge-grounded reply with citations, FAQ answer, status explanation | allowed in AI mode; must carry source citations and the agent/prompt/retrieval versions used |
| **Consequential** | booking or rescheduling, financial or billing change, permission or membership change, deletion request, any external side effect, sending after a human takeover | **human approval required**; the model may only *request* the action |
| **Forbidden to the model** | choosing its own tenant/project scope, calling a disabled tool, reading another project's data, changing AI policy or budgets | denied and recorded, always |

### Rules

1. **The application owns execution.** A model tool request is validated,
   authorized, tenant-scoped, field-limited, capability-checked, and audited
   before it runs. The model never receives data it was not authorized to see.
2. **Human takeover wins.** A human taking over a conversation aborts any
   in-flight AI send. This is a Phase 6 gate and a Phase 10 gate.
3. **No provider, no customer-visible AI.** With no provider key the CRM stays
   fully usable; the conversation falls back to a human or a clearly recorded
   no-action state. The CRM never depends on an AI provider being up.
4. **No chain-of-thought exposure.** Every run records context, retrieval, tool,
   generation, evaluation, and decision stages, and nothing that would expose
   internal reasoning to a customer or an operator UI.
5. **pgvector is an index, not an authorization layer.** Tenant, project, source
   visibility, and effective record policy are applied **before** similarity
   ranking.
6. **Prompt-injection detection is telemetry.** Actual safety comes from policy
   enforcement outside the model, structural framing of untrusted content, and
   tool authorization.
7. **Every AI action is attributable** to agent version, prompt version, model
   and provider, retrieved sources, tool calls, token use, cost, redactions,
   approvals, refusal, override, and final disposition.

---

## Consequences for the remaining phases

- **Phase 8** builds the ledger, rate cards, rollups, period close/lock, and
  reconciliation exactly as defined in Part 1. Margin is reported only for
  priced usage; unpriced usage is a first-class view, not an error.
- **Phase 10** builds the provider abstraction, filtered RAG, and tool registry
  against Part 2. The tool registry is deny-by-default, and the
  approval-required class is wired to the human approval record rather than to a
  prompt instruction.
- **Phase 7** (Admin) must surface AI policy, budgets, blocked calls, and
  approvals without exposing provider secrets.
- **Phase 12** may not enable a customer-visible AI action that lacks the
  policy, approval, and audit trail these decisions require.