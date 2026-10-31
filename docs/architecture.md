# Architecture

FirstElement is four systems that share a database and a tenant model:

```
CRM/event platform + omnichannel messaging + AI agent platform + human support
```

Not "a chatbot with a CRM attached." The AI is one actor inside the CRM,
and the CRM keeps working when the AI provider is down (plan §20.9).

## Processes

| Process | Responsibility |
| --- | --- |
| `apps/api` | HTTP surface, authentication, authorization, domain services |
| `apps/worker` | Everything slow or retryable: AI turns, embeddings, outbound sends, rollups |
| `apps/web` | The operator UI |

PostgreSQL (with pgvector) is the system of record; Redis backs BullMQ.
Nothing that can fail slowly runs inside a request (plan §11).

## Boundaries

```
        ┌──────────────┐        ┌──────────────┐
 web ──▶│   apps/api   │◀──────▶│  PostgreSQL  │
        └──────┬───────┘        └──────▲───────┘
               │ domain events         │
        ┌──────▼───────┐        ┌──────┴───────┐
        │ apps/worker  │◀──────▶│    Redis     │
        └──────────────┘        └──────────────┘
```

**Domain events, not service calls.** Modules publish events
(`ConversationEscalatedEvent`) and routing, notifications, analytics, audit
and realtime subscribe. The AI orchestrator does not call the routing
engine; it announces what happened. The bus is in-process
(`@nestjs/event-emitter`) — BullMQ already covers async work, so no broker
is introduced (plan §8b).

**Connectors are adapters.** WhatsApp, Instagram, Messenger and Telegram
each normalize to `NormalizedIncomingMessage` before anything else sees
them, and declare their differences through `capabilities()` rather than
being assumed identical (plan §8). Platform payloads never reach the CRM
or AI layers.

**The AI is traceable by construction.** Every AI turn produces an `AIRun`
with its retrievals, tool calls, steps and token usage, tied to the exact
`AIAgentVersion` that produced it — so "why did it answer that?" is a
query, not an investigation (plan §7, §9).

## Tenancy

```
Organization → Project → Customer → Conversation → Message
```

Every project-scoped row carries `organizationId` and `projectId`. Access
is resolved from server-side membership on every request, never from a
client-supplied id, and RAG filters by project *before* the vector search
runs. See [database.md](database.md) and [auth.md](auth.md).

## Package map

- `packages/shared` — the wire contract (enums, zod DTOs, error codes)
- `packages/auth` — password hashing, session tokens, the permission matrix
- `packages/database` — Prisma schema and tenant scoping helpers
- `packages/config` — validated environment; the only place secrets are read
- `packages/ui` — design tokens and primitives

- `packages/events` — the domain event vocabulary and envelope

Later slices add `packages/ai`, `packages/connectors`,
`packages/knowledge-base`, `packages/routing`, and `packages/analytics` as
the slices that need them land.

## The conversation timeline

`ConversationEvent` is append-only and has exactly one writer: the
timeline subscriber in `apps/api/src/events/`. Services publish what
happened; the subscriber records it. That keeps one recording path instead
of a timeline insert scattered through every mutation, and a timeline write
failing can never roll back the action it describes.

Message content never travels on the event bus — events carry a body
length, not a body. The text lives in `messages`, where access is already
tenant-scoped.
