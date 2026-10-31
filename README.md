# FirstElement Dental Clinic

A dental clinic management system with patient records, appointments,
odontogram (tooth notation), treatment plans, billing, and AI assistant.

Arabic-first with full RTL, English as an equal second locale.

## Status

Built in vertical slices (see [plan.md](plan.md) §16). Each slice is
runnable end to end before the next begins.

| # | Slice | State |
| --- | --- | --- |
| 1 | Foundation — auth, organization, project, ADMIN/EMPLOYEE roles | **Done** |
| 2 | Core CRM — customer, conversation, message, unified inbox | **Done** |
| 3 | Mock channel — inbound/outbound through the real pipeline | **Done** |
| 4 | Knowledge — upload, processing, embeddings, project-scoped RAG | **Done** |
| 5 | AI agent — agent versions, AI runs, tracing, memory | **Done** |
| 6 | Scoring + escalation — score history, rules, atomic handoff | **Done** |
| 7 | Routing — availability, workload, CSV import, assignment | **Done** |
| 8 | Real connectors — WhatsApp, Instagram, Messenger, Telegram | **Done** |
| 9 | Analytics, notifications, audit surfacing, system health | **Done** |

What works today: sign in, session-cookie auth with server-side RBAC,
organization and project management, employee management with project
membership, audit logging, health check, and the Arabic/English RTL app
shell built on the design system — plus the CRM core: customers with
multi-channel identity resolution, conversations, messages, an append-only
conversation timeline fed by domain events, the three-pane unified inbox
with its saved views, and the customer 360 view.

Messages now flow through the real pipeline: the mock connector accepts a
simulated inbound webhook, the worker resolves the customer, opens or
continues a conversation, stores the message idempotently, and agent
replies are queued for delivery through the connector rather than assumed
sent.

Projects now have knowledge bases: upload or paste documents, watch them
move through UPLOADED → PROCESSING → INDEXING → READY, and test what the
agent would retrieve for a question, with scores, before a customer asks.

Projects with an enabled agent now answer automatically: the orchestrator
retrieves from the project's knowledge, runs permitted tools, replies or
hands over, and records every turn as an AI run an employee can open —
which version answered, which documents it cited, which tools it called,
and what it cost. The bundled mock provider makes all of that work with no
API key.

Every customer turn is now scored — frustration, sentiment, urgency, risk —
and kept as history, with a sparkline in the conversation header showing
the trend against the escalation threshold. Admin-editable rules decide
when the AI hands over, the transition is atomic so it cannot race a manual
takeover, and each escalation arrives with the rule that fired and a
handover summary written for the person inheriting it.

Escalations now get an owner. Routing filters by project membership,
department, skill, availability, working hours in each employee's own
timezone, and workload headroom, then assigns the least-loaded eligible
person — deterministically, so the same escalation always routes the same
way. When nobody qualifies it says which stage blocked it instead of
failing quietly. Employees arrive by CSV or Excel through a wizard that
reports every bad row rather than skipping it.

All four real channels are implemented against the official APIs:
WhatsApp Cloud API, the Messenger Platform for Instagram and Messenger,
and the Telegram Bot API. Credentials are encrypted at rest with
AES-256-GCM and are write-only through the API — no endpoint returns them.
Webhooks are public but verified on every request: Meta signatures over the
raw request bytes, Telegram's secret token, both compared in constant time.

The loop closes with analytics, notifications and system health: an
assignment reaches the agent through a persisted notification and a live
SSE stream, the dashboard leads with the unassigned escalation queue and
then AI resolution rate, response times, AI spend and the knowledge gaps
the agent could not answer, and admins get the audit trail and a health
view of queues, providers and channels.

The plan's §17 acceptance scenario runs end to end as an integration test:
project → employees → knowledge → agent → escalation rule → mock channel →
customer message → AI answer from knowledge → frustration → escalation with
summary → routing → human takeover → resolution → analytics.

## Requirements

- Node 20+ and Yarn (Corepack: `corepack enable`)
- Docker (for PostgreSQL with pgvector, and Redis)

## Quick start

```bash
corepack enable && yarn install
```

```bash
cp .env.example .env
```

Generate the two secrets `.env` needs:

```bash
node -e "console.log('SESSION_SECRET=' + require('crypto').randomBytes(24).toString('hex')); console.log('ENCRYPTION_KEY=' + require('crypto').randomBytes(32).toString('hex'))"
```

Start the datastores:

```bash
docker compose up -d postgres redis
```

Create the schema and demo data:

```bash
yarn db:generate && yarn db:push && yarn db:seed
```

Run the API and the web app in two terminals:

```bash
yarn dev:api
```

```bash
yarn dev:web
```

The app is at http://localhost:5173 and the API at http://localhost:4000/api.
Seeded sign-in: `admin@firstelement.dev` / `DemoPassword123!` (demo data
only — see `packages/database/src/seed.ts`).

## Checks

```bash
yarn typecheck && yarn lint && yarn test
```

The integration suites need a reachable PostgreSQL with pgvector; without
one they skip rather than passing silently. `vitest.config.ts` loads
`.env`, so `DATABASE_URL` reaches the test process — without that, the
suites skip themselves and a green run proves nothing.

## Layout

```
apps/api        NestJS API — auth, RBAC, tenant-scoped **clinic** modules
apps/web        Angular standalone client (Arabic-first, RTL)
apps/worker     BullMQ worker process
packages/auth   Password hashing, session tokens, cookie handling
packages/config Validated environment configuration
packages/events Domain event vocabulary and envelope (plan §8b)
packages/connectors Messaging adapters, capability table, MockConnector
packages/messaging Identity resolution, inbound pipeline, outbound dispatch
packages/ai Providers, tools, orchestrator, run tracing, embeddings
packages/knowledge-base Parsing, chunking, ingestion, embeddings, retrieval
packages/routing Assignment pipeline, working hours, employee import
packages/database Prisma schema, migrations, tenant scoping helpers, seed
packages/shared Enums, zod DTOs and error codes shared by API and web
packages/ui Design system: tokens, primitives, RTL direction support
```

## V1.2

Agent status with per-status timers and time reporting, employee
administration (accounts, activation, project assignment, per-agent
conversation limits), configurable case classification with a
project-scoped knowledge lookup, the case solution record with close and
reopen, CSAT/DSAT/AHT/resolve-rate on the dashboard, and the inbox as a
floating window that keeps the conversation live while you use the rest of
the CRM.

See [docs/v1.2-changes.md](docs/v1.2-changes.md) — including the bugs found
along the way, one of which had been hiding every integration test.

## Documentation

- [docs/architecture.md](docs/architecture.md) — system shape and boundaries
- [docs/local-dev.md](docs/local-dev.md) — running and troubleshooting
- [docs/database.md](docs/database.md) — schema, migrations, tenancy rules
- [docs/auth.md](docs/auth.md) — sessions, RBAC, threat notes
- [docs/connectors.md](docs/connectors.md) — channels, capabilities, idempotency
- [docs/rag.md](docs/rag.md) — knowledge base, chunking, embeddings, retrieval
- [docs/ai-system.md](docs/ai-system.md) — agent versions, tools, tracing, injection defense
- [docs/scoring-escalation.md](docs/scoring-escalation.md) — signals, rules, the atomic handover
- [docs/routing.md](docs/routing.md) — the assignment pipeline, working hours, import
- [docs/deployment.md](docs/deployment.md) — processes, migrations, webhooks, what to watch
- [docs/v1.2-changes.md](docs/v1.2-changes.md) — V1.2 scope, decisions and findings
- [docs/design-system.md](docs/design-system.md) — the visual brief and why
- [docs/testing.md](docs/testing.md) — what is tested and how to run it
