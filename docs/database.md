# Database

PostgreSQL 16 with pgvector, accessed through Prisma. Schema:
`packages/database/prisma/schema.prisma`.

## Tenancy rules

1. Every project-scoped table carries `organizationId` **and** `projectId`.
2. Every query filters on both, via the helpers in
   `packages/database/src/tenant.ts` — `projectScope`,
   `accessibleProjectsScope`, `organizationScope`. Filtering by `projectId`
   alone is a bug even when it looks sufficient.
3. `projectId` from a client is validated against server-resolved
   membership before it reaches a query.
4. Knowledge retrieval filters by project **before** the vector similarity
   search, never after (plan §6).
5. A user with no memberships sees nothing. `accessibleProjectsScope`
   returns an empty `in` list rather than degrading to an unfiltered query.

`apps/api/test/tenant-isolation.integration.test.ts` proves these against a
real database.

## Tables

| Table | Notes |
| --- | --- |
| `organizations` | Tenant root |
| `projects` | Slug unique per organization, not globally |
| `users` | Login principal; email unique globally |
| `employees` | Availability and workload, one per user |
| `project_memberships` | The single source of project access for every role |
| `sessions` | SHA-256 token hashes, expiry, revocation |
| `audit_logs` | Actor, action, resource, project, metadata |
| `customers` | A resolved person, project-scoped |
| `customer_identities` | One external handle; unique per `(project, platform, externalId)` |
| `conversations` | Channel, status, mode, priority, assignee, `modeVersion` |
| `messages` | Direction, author type, body, delivery status, provider id |
| `conversation_events` | Append-only timeline behind the thread view |

Later slices add channel connections, AI agents/versions/runs, knowledge
bases, documents, chunks, scores, escalations, routing rules, and
notifications (plan §7).

### Two constraints worth knowing about

`messages` carries `@@unique([projectId, channel, externalMessageId])`.
That is what makes webhook processing idempotent in slice 3 — a redelivered
webhook is rejected by the database rather than by an application-level
"check then insert", which races under concurrency. NULL external ids do
not collide in Postgres, so internally-authored messages are unaffected.

`conversations.modeVersion` exists for slice 6: the `AI → HUMAN` transition
is an optimistic-concurrency update, so an AI response already in flight
cannot send after a human takes over (plan §10).

## Conventions

- UUID primary keys, `createdAt` / `updatedAt` on every mutable table.
- `camelCase` in code, `snake_case` tables via `@@map`.
- Index anything you filter or sort by, leading with the tenant column:
  `@@index([organizationId, createdAt])`.
- No raw string SQL. Vector queries use parameterized `$queryRaw` tagged
  templates; `$queryRawUnsafe` is blocked by lint.

## Migrations

```bash
yarn db:migrate
```

`yarn db:push` is for fast dev iteration only. Anything that reaches a
shared environment goes through a migration, applied with
`yarn workspace @firstelement/database prisma:deploy`.

The initial migration in `prisma/migrations/20260814000000_init` was
generated with `prisma migrate diff` and creates the `pgcrypto` and
`vector` extensions before any table.

## Seed

`yarn db:seed` creates one clearly-labelled demo organization with two
projects, an admin, three employees, and deliberately partial project
membership so isolation tests have a real negative case. It refuses to run
with `NODE_ENV=production` and is never imported by application code.
