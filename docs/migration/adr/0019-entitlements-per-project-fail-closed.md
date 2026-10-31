# ADR 0019: Feature entitlements are per project, and absence means asleep

- Status: accepted
- Date: 2026-10-12
- Phase: clinic / platform foundation
- Scope: `DEC-012`, `DEC-013` — seats, feature flags, and the AI sleep mode
- Raised by: product owner
- Decided by: product owner

## Context

FirstElement sells per clinic. A clinic buys a seat count and a set of features, and two clinics in
the same deployment will buy different things.

The codebase has no concept of a plan, a seat, or an entitlement. The only related switch is
`firstelement.ai.enabled`, which is a deployment-global property read at boot — it cannot express
"clinic A has AI, clinic B does not".

That matters because the AI path is the expensive one. `AiResponseTrigger` currently enqueues an AI
turn for any inbound message on an AI-mode conversation. With a global flag there is nowhere to
express a per-clinic decision, so an entitlement check has to become a runtime lookup against the
project the message arrived on.

There is also a precedent in this schema that argues for the polarity of the decision.
`appointments.isAiProposed` carries `DEFAULT true`: a row inserted without specifying it is silently
recorded as AI-proposed. A default-on entitlement flag in the same schema would run AI for clinics
that never paid for it.

## Decision

Feature availability is **per project**, chosen by the platform owner when the clinic is created and
changeable afterwards. Seat limits are per project as well.

**Absence means asleep.** An unconfigured project, an unrecognised plan name, a failed lookup, or a
missing row all resolve to *not entitled*. There is no default-on path, and no code path may treat
"could not determine" as "allowed".

**An asleep clinic produces no AI activity of any kind:**

- no AI turn is enqueued — the gate sits in the trigger, before a run row exists and before any
  provider is called;
- no AI tool executes;
- no budget consumption is posted and no cost is incurred;
- the draft panel and the AI route are disabled in the interface with an upgrade message.

**The AI agent and the AI knowledge base are separate entitlements.** A clinic may hold the agent
without the knowledge base; the agent then answers from structured facts and tools only. The
knowledge-base flag is enforced in retrieval itself, not only at the AI gate — otherwise "AI without
KB" silently becomes "AI with every clinic's knowledge", which is a cross-tenant disclosure dressed as
a product tier.

**Seats are enforced twice:** at account creation and at sign-in. A limit enforced only in the
interface is a billing failure, not a control.

Entitlement state is returned to the client so the interface can grey out locked features, and it is
resolved against the **currently selected project**, so that a user who works in two clinics does not
see one clinic's features while looking at the other's data.

## Consequences

- `firstelement.ai.enabled` remains a **deployment kill switch** and is not replaced by entitlements.
  It stays useful for turning AI off everywhere during an incident. Entitlements are the per-clinic
  layer beneath it; both must permit a turn.
- Every path that can start AI work must consult the entitlement, not only the trigger. A tool invoked
  directly, a replay, and a worker retry are separate entry points.
- Enabling a feature for a clinic is an auditable platform action, like any other change that affects
  what a customer may do.
- The same mechanism generalises: any future paid capability is an entitlement, and the interface has
  one place to learn what the current clinic may use.