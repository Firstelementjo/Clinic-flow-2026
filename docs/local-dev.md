# Local development

## Setup

```bash
corepack enable && yarn install && cp .env.example .env
```

Fill in `SESSION_SECRET` (32+ chars) and `ENCRYPTION_KEY` (64 hex chars):

```bash
node -e "console.log('SESSION_SECRET=' + require('crypto').randomBytes(24).toString('hex')); console.log('ENCRYPTION_KEY=' + require('crypto').randomBytes(32).toString('hex'))"
```

The API refuses to start on an invalid environment rather than failing at
the first request — that check lives in `packages/config`.

## Datastores

```bash
docker compose up -d postgres redis
```

The Postgres image is `pgvector/pgvector:pg16`; plain `postgres:16` will
fail the `vector` extension in the first migration.

```bash
yarn db:generate && yarn db:push && yarn db:seed
```

`docker compose --profile app up` additionally runs the API and worker in
containers. Day to day it is faster to run them on the host.

## Running

```bash
yarn dev:api
```

```bash
yarn dev:web
```

Vite proxies `/api` to the API, so the browser talks to one origin in dev
exactly as it will in production — no SameSite surprises at deploy time.

The worker starts with `yarn dev:worker`. In slice 1 it verifies its
dependencies and idles; processors arrive with the slices that need them.

## Checks

```bash
yarn typecheck && yarn lint && yarn test
```

## Troubleshooting

**`Cannot find module '@firstelement/...'`** — workspace packages are
consumed as built output. Run `yarn typecheck` (which builds project
references) after changing `packages/*`.

**`Environment variable not found: DATABASE_URL`** — `.env` is missing or
unreadable from the process's working directory. Tests report this as
skipped integration suites, not failures.

**Prisma client out of date after a schema edit** — `yarn db:generate`.

**Windows:** commands are shown in bash; PowerShell equivalents differ for
`cp` and inline env vars. Docker Desktop must be running before
`docker compose up`.
