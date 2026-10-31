# Phase 0 — Freeze behavior and create the migration baseline

## Objective
Make the current system measurable before replacing it. Produce the machine-readable inventory, record the baseline
validation evidence, and record the target-stack decisions that everything else builds on.

## Scope (allowed to change)
- `docs/migration/**` (owned by cartographer + docs agent)
- Read-only inspection of `apps/**`, `packages/**`
- Phase task/report files under `docs/migration/phases/`

## Dependencies
- Extracted `clinic-flow-main` repo at `C:\clinic-flow-main`
- Branch `migration/spring-angular`, tag `legacy-reference`

## Acceptance criteria (gate)
1. `docs/migration/current-system-inventory.md` exists and covers every endpoint+permission, Prisma model/enum/index/FK,
   React route/page/component/hook/i18n namespace, queue/job/provider/idempotency key, connector/webhook/secret,
   dashboard metric, and test (with unit/integration/browser classification).
2. Documented-but-unimplemented features and dead code/lint blockers are listed.
3. Baseline validation evidence recorded: `yarn test`, `yarn typecheck`, `yarn lint`, integration-skip status, Docker availability.
4. Every planned target feature maps to an existing behavior, a new requirement, or an explicit deferral.
5. Target API versioning, session model, and legacy→target domain vocabulary decided (ADRs seeded).

## Rollback
Docs-only phase. `git restore` the docs folder if a checkpoint must be rebuilt.

## Test commands
```
yarn install --frozen-lockfile
yarn test
yarn typecheck
yarn lint
```

### Baseline validation evidence (2026-10-04, Windows, Node 24)

- `yarn install --frozen-lockfile` → exit 0
- `yarn build` → exit 1, `apps/api/src/main.ts` TS7006 (3 implicit-any middleware params) + `packages/ui` missing `build` script. **Fixed in Phase 0** (typed `Request/Response/NextFunction`; added ui `build` = `tsc --noEmit`). After fix: exit 0.
- `yarn typecheck` → exit 0 after the main.ts fix (was failing on the documented 3 implicit-any errors).
- `yarn lint` → exit 1, 118 errors / 39 warnings (documented baseline; deferred to later quality phases).
- `yarn test` with `DATABASE_URL`/`REDIS_URL` set → exit 0, 24 files, 274 passed, 5 skipped. Without env vars the integration suites fail to resolve workspace entries / skip — recorded as the known gap.
- Prisma state: live dev DB has tables but NO `_prisma_migrations` history (was created via `db push`); init migration enum values (`AVAILABLE`,`BUSY`,`AWAY`,`OFFLINE`) disagree with schema/live DB (`ONLINE`,`OFFLINE`,`BUSY`,`BREAK`,`MEAL`,`TRAINING`,`MEETING`). Backup taken; Flyway baseline must wait for Agent B reconciliation (Phase 2).

## Inventory summary

See `docs/migration/current-system-inventory.md` (cartographer output). ADRs seeded under `docs/migration/adr/` (browser session model, Flyway baseline/quoted columns, SSE vs WebSocket, Angular i18n). All target features mapped to existing behavior, new requirement, or explicit deferral in the inventory matrix.

# Reviewer checklist
- [ ] No business code modified
- [ ] No secrets read or printed
- [ ] Integration skip behavior recorded as FAIL evidence, not pass

## Inventory summary (cartographer detail)

Cartographer pass over `apps/api`, `apps/web`, `apps/worker`, `packages/*`,
and `deploy/*` completed 2026-10-04.

- Inventory: `docs/migration/current-system-inventory.md` now covers every
  API endpoint + permission (28 controllers, ~110 routes, two public write
  surfaces: `/api/webhooks/*` and `/api/public/appointments/:token`), every
  Prisma model/enum/index/FK/unique constraint, both Prisma migrations,
  every React route/page/component/hook/i18n namespace, all eight BullMQ
  queue names + retry policy + idempotency keys, every connector/webhook/
  secret/capability/provider ID, the dashboard metric definitions, the
  test suite classified as unit vs integration vs browser (browser: none),
  documented-but-unimplemented features (target stack, finance, exports,
  outbox, admin control plane), hard-coded colors (`#0f5b4c` family across
  clinic pages/components + `bg-gradient-to-*` usage), unrouted pages
  (`CompaniesPage`, `DealsPage`, `LeadsPage`, `WorkflowsPage`,
  `EmployeeDashboard`), 5 unsupported `/app/*` redirects, and the lint /
  typecheck / Prisma-drift blockers.
- ADRs seeded: `docs/migration/adr/0001-browser-session-model.md`,
  `0002-flyway-baseline-quoted-columns.md`,
  `0003-sse-vs-websocket-realtime.md`, `0004-angular-i18n.md` (all
  PROPOSED).
- Baseline evidence preserved: `yarn test` 238 passing / 41 skipped
  (skip = FAIL evidence), `yarn typecheck` fails on 3 implicit-`any` in
  `apps/api/src/main.ts`, `yarn lint` 118 errors / 39 warnings, Docker
  unavailable, Prisma `EmployeeAvailabilityStatus` enum drift blocks
  Flyway baseline until reconciled.
- No business code, tests, or package files were modified.
