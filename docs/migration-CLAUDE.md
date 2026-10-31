# Working in this repository

FirstElement — an AI-native omnichannel CRM. `plan.md` is the execution
contract; this file is the operating manual for changing the code.

## Before you write code

1. Read `plan.md` for the slice you are on (§16 defines the build order).
2. A slice is done when it is runnable and usable end to end, with
   typecheck, lint, and tests green — not when the code exists.

## Commands

```bash
yarn typecheck && yarn lint && yarn test
```

- `yarn dev:api` / `yarn dev:web` / `yarn dev:worker`
- `yarn db:push` (dev schema sync), `yarn db:migrate` (create a migration),
  `yarn db:seed`
- Packages are consumed as built output. After changing `packages/*`, run
  `yarn typecheck` (which builds project references) before starting the API.

## Invariants — do not violate these without changing plan.md first

**Tenancy.** Every project-scoped query goes through `projectScope(ctx, id)`
or `accessibleProjectsScope(ctx)` from `@firstelement/database`. A
`projectId` from a client is a request, never an authorization. Never
filter by `projectId` alone — `organizationId` rides along so a membership
bug still cannot cross tenants.

**Authorization.** Add a capability to `Permission` in
`packages/auth/src/policy.ts` and declare it on the route with
`@RequirePermissions(...)`. Never write `if (user.role === 'ADMIN')` in a
service. Frontend route guards are UX; the server is the boundary.

**Errors.** Access failures surface as 404 through `HttpExceptionFilter`,
because "this exists but is not yours" is itself a leak. Do not special-case
this in controllers.

**Secrets.** API keys, connector credentials, and system prompts never
reach the frontend. Config is read through `@firstelement/config`; the only
values allowed in the browser are those in `publicConfig()`.

**Validation.** Every request body is validated with a zod schema from
`@firstelement/shared` via `zodBody(schema)`. The web app imports the same
schema, so a contract change breaks both sides at compile time.

**Audit.** Sensitive actions call `AuditService.record` with the action
constants in `@firstelement/shared`. Never log customer message content.

**Design.** Colors, spacing and type come from `packages/ui` tokens. A hex
value in a feature component is a bug. Use CSS logical properties
(`ms-auto`, `start-0`, `padding-inline-start`) so RTL works without a
second stylesheet. Read `docs/design-system.md` before adding UI.

## Conventions

- TypeScript strict everywhere; no `any` in new code.
- Backend packages compile to CommonJS; `apps/web` and `packages/ui` are
  bundled by Vite as ESM.
- Prisma models are `camelCase` in code and `snake_case` in the database via
  `@@map`; index anything you filter or sort by.
- Long-running work belongs in a BullMQ queue (`apps/worker/src/queues.ts`),
  never inline in an HTTP request.
- Modules communicate through domain events (plan §8b) rather than reaching
  into each other's services.
