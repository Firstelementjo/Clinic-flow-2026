# Deployment

## Processes

Three, and they are not interchangeable:

| Process | Command | Notes |
| --- | --- | --- |
| API | `yarn workspace @firstelement/api start` | Stateless; scale horizontally (but see *Realtime* below) |
| Worker | `yarn workspace @firstelement/worker start` | Owns AI turns, scoring, routing, ingestion, outbound sends |
| Web | static build from `apps/web/dist` | Any static host or CDN |

The worker is not optional. Without it, messages arrive and nothing
processes them — the queue depth on the system health page is the first
place that shows up.

## Required services

- **PostgreSQL 16 with pgvector.** The `vector` extension is created by
  the initial migration; the HNSW index is a hand-written migration
  (`20260814000100_vector_index`) that must not be dropped when
  regenerating migrations.
- **Redis 7** for BullMQ. `QUEUE_DRIVER=inline` exists for development
  only and configuration rejects it in production, because inline work
  dies with the process.

## Environment

Every variable is validated at boot by `@firstelement/config`; a bad
environment fails fast rather than at the first request. Two must be
generated per environment and never shared between them:

```bash
node -e "console.log('SESSION_SECRET=' + require('crypto').randomBytes(24).toString('hex')); console.log('ENCRYPTION_KEY=' + require('crypto').randomBytes(32).toString('hex'))"
```

**`ENCRYPTION_KEY` is not rotatable without a migration.** It decrypts
stored connector credentials. Changing it makes every channel report as
needing reconnection — which is deliberate (a failed decrypt is treated as
"not configured" rather than crashing), but it means re-entering every
channel's credentials.

Production also refuses `ENABLE_MOCK_CONNECTOR=true`: an endpoint that
injects customer messages is a way to impersonate customers.

## The database must run in UTC

```sql
ALTER DATABASE firstelement SET TimeZone TO 'UTC';
```

This is not a preference, it is a correctness requirement. Prisma maps
`DateTime` to `timestamp without time zone` and reads every value back as
UTC. Columns with `@default(now())` are filled by PostgreSQL's
`CURRENT_TIMESTAMP`, which returns **the server's local time** — so on a
database running in, say, `Asia/Amman`, every row created without an
explicit timestamp lands three hours in the future.

Nothing errors. What happens instead is quieter: "last 30 days" windows
include and exclude the wrong rows, first-response and resolution times go
negative or absurd, agent status durations misreport, and the inbox sorts
by a `lastMessageAt` that disagrees with the message it came from.

Values written by the application are unaffected — Node sends real UTC.
It is only the database-side defaults that skew, which is what makes it
easy to miss.

## Migrations

```bash
yarn workspace @firstelement/database prisma:deploy
```

Run before starting the new API version. `db:push` is a development
convenience and skips the migration history — do not use it against a
database you care about.

## Webhooks

Public HTTPS is required — Telegram will not register an HTTP webhook, and
Meta's signature scheme assumes transport security. Register:

```
https://<api-host>/api/webhooks/meta/<connectionId>
https://<api-host>/api/webhooks/telegram/<connectionId>
```

Behind a proxy, make sure the raw request body reaches the API unmodified.
A proxy that re-serializes JSON breaks Meta signature verification, and the
symptom is every webhook 404ing with `Webhook signature verification
failed` on the channel panel.

## Realtime and horizontal scaling

The SSE stream is backed by an **in-process** subject. One API instance is
fine. With several behind a load balancer, a client connected to instance A
will not see events published on instance B — notifications still arrive
(they are persisted and polled), but live inbox updates will be partial.

Fixing it properly means bridging the event bus over Redis pub/sub. That is
not built, and it is the main thing standing between this and a multi-
instance deployment.

Sticky sessions are *not* a fix — they make each instance's stream
self-consistent while still dropping cross-instance events.

## Scaling the worker

Multiple worker instances are safe: BullMQ distributes jobs, and every
stage is idempotent (webhook dedup via a unique index, one AI turn per
message id, outbound sends skip an already-delivered message). Concurrency
is set per queue in `apps/worker/src/main.ts` — document processing is
deliberately low, because embedding is the billed, rate-limited step.

## What to watch

The system health page (admin) reports what actually goes wrong:

- **Queue depth.** `waiting` climbing while `active` is zero means the
  worker is down.
- **Failed jobs.** Retries are exhausted after five attempts with
  exponential backoff; what is left needs a person.
- **Stalled documents.** Anything `PROCESSING`/`INDEXING` for over an hour
  is orphaned, not slow.
- **Channel errors.** A failing webhook signature or send shows up here
  before customers notice.

## Backups

`document_chunks` can be rebuilt by re-processing documents, but the
uploaded files themselves cannot — back up `STORAGE_LOCAL_PATH` (or use
S3). Everything else lives in PostgreSQL; the conversation and audit
tables are append-only in practice and compress well.
