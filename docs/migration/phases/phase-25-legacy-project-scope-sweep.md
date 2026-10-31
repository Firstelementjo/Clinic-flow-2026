# Phase 25 — legacy project/tenant scope sweep: one class, seven more sites (P0)

Status: **one module fixed, six reported as BLOCKED or needs-a-human**. One new
production file changed, one new suite of 21 tests, one shared harness module.
No migration. No permission change. The Spring side was verified, not assumed, and
was not touched.

| | |
|---|---|
| Trigger | Phase 24 found the notes defect and reported three sibling sites it could not fix |
| Mission | Sweep every legacy `create`/`read`/write that takes a project or tenant id from the request, and give each site a verdict |
| Sites found **vulnerable** | **9** across `apps/api/src/**` (notes and tasks now fixed; 7 remain open) |
| **Fixed here** | `apps/api/src/tasks/tasks.service.ts` — 5 sites, +27 / −8 |
| **Blocked — needs a file outside my globs** | `clinic/*`, `reminders.service.ts`, `ai-simulator.service.ts` |
| **Needs a human** | `analytics.controller.ts` (authorization in the controller) |
| Production change | `apps/api/src/tasks/tasks.service.ts` only |
| Verification | `backend/crm-core/src/test/legacy-notes-project-scope/tasks-project-scope.test.ts` — 21 tests. Against the pre-fix code, **12 of the 21 fail** |
| Migration impact | **none** |
| Spring side | **no live instance found**; two structural observations recorded in §7 |

---

## 1. The shared pattern — one helper's misuse explains the class

Yes. There is a single shared helper, and its contract is narrower than the code
assumed. `apps/api/src/auth/permissions.guard.ts`:

```ts
const projectId = (request.params as Record<string, string | undefined>)?.projectId;

for (const permission of required) {
  assertCan(actor, permission, projectId ? { projectId } : {});
}
```

`can()` in `packages/auth/src/policy.ts` only checks the project boundary when one
is supplied:

```ts
// Project boundary: membership is required even for an ADMIN…
if (resource.projectId && !actor.projectIds.includes(resource.projectId)) {
  return false;
}
```

So the rule is:

> **A route that names the project in its path (`:projectId`) is membership-checked
> by `PermissionsGuard` before the handler runs. A route that names the project in a
> query string or a request body gets `{}` — no project scope — and the handler is
> the only remaining layer.**

Every vulnerable site below is that second shape, and in every one of them the
handler assumed the guard had already checked. Notes was not a one-off mistake; it
was the first place the assumption was noticed.

And the guard's own Javadoc repeats the false claim that made it invisible:

> *"if the route carries a `:projectId`, it is checked … **A handler therefore
> cannot forget to scope itself.**"*

That sentence is false for every query/body route — the same shape of misleading
comment as the notes Javadoc, and it belongs in `permissions.guard.ts`, which this
mission did not authorise. **Flagged, not fixed.**

## 2. Reachability is measured, not assumed

A `projectId` absent from a controller's `@Body()` **TypeScript type** is not a
security boundary: the type is erased at runtime, and `ValidationPipe({ whitelist:
true })` (`apps/api/src/main.ts:49`) only strips undeclared properties from **class
DTOs**. `@Body() body: { title: string }` and `@Body() body: any` both produce a
runtime metatype of `Object`, which `ValidationPipe.toValidate()` skips.

`validation-pipe-reachability.test.ts` boots a real Nest app with the byte-for-byte
pipe configuration from `main.ts:49` and proves it:

| Handler shape | undeclared `projectId` / `organizationId` |
|---|---|
| `@Body() body: { title: string }` (as `tasks.controller.ts`) | **survives** |
| `@Body() body: any` (as `clinic.controller.ts`) | **survives** |
| class DTO with `@IsString()` | stripped |

The DTO control cannot be expressed through decorators in this harness because
esbuild does not implement `emitDecoratorMetadata`, so it calls the pipe's decision
function directly with each metatype instead. Both halves matter: the two positive
results show the pass-through, the control shows the pipe is not simply broken.

**Consequence:** the absence of `projectId` from `tasks.controller.ts`'s body type
was never a defence, and the task create/update holes are reachable over HTTP.

## 3. The full sweep, with a verdict per site

Every row was read in the code, not inferred from a pattern match. "Same org, wrong
project" means `organizationId` was pinned server-side and the project was not.

### Fixed in phase 24

| Site | Verdict | Note |
|---|---|---|
| `notes/notes.service.ts` — all 8 routes | **vulnerable → fixed** | same class, same guard gap (`:projectId` absent from the notes routes) |

### Fixed in this phase

| Site | Verdict | Mechanism |
|---|---|---|
| `tasks.service.ts:38` `list` | **vulnerable → fixed** | `filters.projectId` from the query string → other project's tasks |
| `tasks.service.ts:75` `getById` | **vulnerable → fixed** | `{ id, organizationId }` — organization only, no project |
| `tasks.service.ts:109` `create` | **vulnerable → fixed** | `input.projectId` from the body |
| `tasks.service.ts:157` `update` — `organizationId` | **vulnerable → fixed** | **cross-tenant**: caller could move a task into another tenant |
| `tasks.service.ts:158` `update` — `projectId` | **vulnerable → fixed** | move a task into a project the caller does not hold |
| `tasks.service.ts:193` `assertOwned` | **vulnerable → fixed** | the whole write boundary for `update` and `delete` |

### Reported — BLOCKED, a file outside this mission's globs is required

| Site | Verdict | Why it cannot be fixed from here |
|---|---|---|
| `clinic/clinic.controller.ts:52-56` `POST /clinic/doctors` | **vulnerable, cross-tenant** | `{ organizationId: ctx.organizationId, projectId: ctx.projectIds[0] \|\| '', ...body }` — the spread comes **after**, so `body.organizationId` overrides the tenant. A cross-tenant write primitive. The fix is in the controller. |
| `clinic/clinic.controller.ts:94-98` `POST /clinic/procedures` | **vulnerable, cross-tenant** | same shape |
| `clinic/clinic.controller.ts:59-70` `PATCH`/`DELETE /clinic/doctors/:id` | **vulnerable, cross-tenant** | the handler has **no `@Tenant()` at all**; it calls `updateDoctor(id, body)` / `deleteDoctor(id)`. The adapter methods take no `TenantContext`, so scoping them requires changing the caller — the controller |
| `clinic/clinic.controller.ts:101-112` `PATCH`/`DELETE /clinic/procedures/:id` | **vulnerable, cross-tenant** | same |
| `local-clinic.adapter.ts:123-131` `updateDoctor` | **vulnerable, cross-tenant** | `findUnique({ id })` then `update({ where: { id } })` — no `organizationId`, no `projectId` |
| `local-clinic.adapter.ts:134-138` `deleteDoctor` | **vulnerable, cross-tenant** | `delete({ where: { id } })` |
| `local-clinic.adapter.ts:174-177` `updateProcedure` | **vulnerable, cross-tenant** | `update({ where: { id } })` |
| `local-clinic.adapter.ts:180-184` `deleteProcedure` | **vulnerable, cross-tenant** | `delete({ where: { id } })` |
| `local-clinic.adapter.ts:186-222` `getAllDoctors` / `getAllProcedures` / `getProcedures` | **vulnerable, cross-tenant read** | `where: { projectId }` with **no `organizationId`**. The controller passes a caller-supplied `projectId` (`GET /clinic/doctors/manage`, `GET /clinic/procedures/manage`), so a known project UUID in another tenant discloses names, specialties, emails and phones |
| `local-clinic.adapter.ts:225` `getProcedureById`, `:240` `getDoctorById` | **vulnerable, cross-tenant read** | `findUnique({ id })` |
| `clinic/reminders.service.ts:259` + `:283` | **vulnerable, cross-tenant write, no permission** | `appointment.findFirst({ where: { id } })` then `update({ where: { id } })`. Reached by `POST /clinic/reminders/respond`, which has **no `@Tenant()` and no `@RequirePermissions`** — any authenticated principal, any tenant, can set any appointment's status and append to its notes |
| `clinic/ai-simulator.service.ts:70,80-81` | **vulnerable, cross-tenant read** | `dto.projectId \|\| ctx.projectIds[0]` from `@Body() body: any`, then `getDoctors`/`getProcedures`, which are project-scoped but organization-blind |
| `appointments/appointments.service.ts:98,141` | **vulnerable** | `projectId` from query / body, no `projectScope` import anywhere in the file |
| `appointments/appointments.service.ts:129,209,242` | **vulnerable** | `getById` / `update` / `delete` bounded by `organizationId` only |
| `appointments/appointments.service.ts:149` | **vulnerable, cross-tenant read** | `customer.findUnique({ where: { id: input.customerId } })` — no organization predicate; copies the other tenant's `displayName`, `primaryPhone`, `primaryEmail` and `ticketNumber` into the caller's appointment |
| `deals/deals.service.ts:16,117` | **vulnerable** | `projectId ?? defaultProjectId(ctx)` from query / body; no `projectScope` import |
| `leads/leads.service.ts:16,133` | **vulnerable** | same shape |

### Needs a human

| Site | Verdict | Why |
|---|---|---|
| `analytics/analytics.controller.ts:74-78` | **vulnerable** + architectural | a caller-supplied `projectId` is inlined straight into a `where` clause **inside the controller**: `...(projectId ? { projectId } : {})`. `organizationId` is pinned, so it is a same-org cross-project read of audit logs (actor names, emails, metadata). It is also authorization performed in the controller, which `plan.md` §0.4 forbids outright. Moving it to the service is a design decision, not a one-line patch |

### Swept and safe

Recorded so they are not re-opened. Each was read.

| Site | Verdict | Why it is safe |
|---|---|---|
| `notifications/notifications.service.ts:109` | **safe** | **my phase-24 flag was wrong.** `create` is an internal method: the only callers are the service's own event handlers, which pass `event.projectId`. `NotificationsController` never calls it |
| `local-clinic.adapter.ts:302` `createAppointment` | **safe** | **my phase-24 flag was correct but the verdict is safe.** The only caller is `clinic.service.ts:114`, which passes `projectId = this.getProjectId(ctx)` — purely server-derived |
| `views/views.service.ts:93` | **safe** (as instructed) | `canAccessProject` at line 90 throws a 404 first, and line 128 re-checks the row's own project |
| `accounts/accounts.service.ts` (4 sites) | **safe** (as instructed) | `Account` has no `projectId` column; organization-scoped by design |
| `cases/cases.service.ts:162,335` | safe | `projectId` comes from the conversation, loaded via `accessibleProjectsScope` |
| `conversations/conversations.service.ts:180` | safe | `conversation.projectId` from `loadForWrite` |
| `channels/mock-webhook.controller.ts:63` | safe | validated by `requireEnabled` → `projectScope` on line 48; its comment is accurate |
| `ai/ai.providers.ts:20` | safe | provider plumbing fed by the orchestrator, not a request |
| `routing/agent-status.service.ts` | safe | writes keyed on the caller's employee and `ctx.organizationId`; no project id from a request |
| `escalation`, `knowledge`, `channels`, `routing`, `employees`, `ai/agents`, `projects`, `customers`, `analytics.service` | safe | all scope through `projectScope` / `accessibleProjectsScope` / `filterProjects` |

## 4. Total

**9 vulnerable sites** confirmed by reading code:

| Where | Count | State |
|---|---|---|
| `notes` (phase 24) | 1 helper, 8 routes | **fixed** |
| `tasks` (this phase) | 5 sites / 6 routes | **fixed** |
| `clinic` (controller + adapter + reminders + ai-simulator) | 13 sites | **open — blocked** |
| `appointments`, `deals`, `leads` | 7 sites | **open — out of globs** |
| `analytics.controller` | 1 site | **open — needs a design decision** |

The clinic cluster is **more severe than everything this mission fixed**: several of
those sites cross the *tenant* boundary, not just the project boundary, and two of
them (`POST /clinic/doctors`, `POST /clinic/reminders/respond`) additionally have
**no `@RequirePermissions` on the route at all** — `ClinicController` declares no
permission on any handler, so every clinic endpoint requires only a valid session.

## 5. Why `local-clinic.adapter.ts` was left untouched

The file is inside this mission's globs, and it genuinely contains the defects
listed in §3. It is not fixed because **every one of them needs `TenantContext` to
reach the adapter, and the only callers are in `clinic.controller.ts`, which is
read-only here**:

- `updateDoctor(doctorId, data)` and `deleteDoctor(doctorId)` take no context at
  all. Adding a required `ctx` parameter breaks the controller's compile; adding an
  optional one that hard-fails on absence makes every clinic route return 404. Both
  are worse than the current state.
- `createDoctor` / `createProcedure` receive `{ organizationId, projectId, ...body }`
  from the controller, where the spread already overrode both. Validating inside the
  adapter is impossible without the caller's membership.

A half-fix here would either break the build or break the clinic module at runtime.
The correct fix threads `TenantContext` from the controller into the adapter and
moves the `{ ...body }` spread **before** the server-derived fields. That is a
change to `clinic.controller.ts` and needs authorisation.

## 6. The change

```ts
// apps/api/src/tasks/tasks.service.ts
private resolveProject(ctx: TenantContext, requestedProjectId?: string): string | undefined {
  return requestedProjectId
    ? projectScope(ctx, requestedProjectId).projectId
    : this.defaultProjectId(ctx);
}
```

- `list` and `create` route their caller-supplied id through it.
- `getById` and `assertOwned` switch from `{ id, organizationId }` to
  `{ id, ...accessibleProjectsScope(ctx) }`.
- `update` validates a supplied `projectId` through `projectScope`, and
  **no longer copies `input.organizationId`** onto the row.

The unpinned branch deliberately keeps `defaultProjectId(ctx)` (`ctx.projectIds[0]`)
rather than widening to all accessible projects. It is already membership-derived, so
it is safe, and widening it would have been a functional change beyond the defect.

The same two helpers as phase 24, from the same package, already imported by nine
other modules. Nothing new was invented, and **nothing outside
`apps/api/src/tasks/tasks.service.ts` was modified** — `notifications.service.ts` and
`local-clinic.adapter.ts` were assessed and deliberately left alone.

### Red-to-green evidence

| | notes (phase 24) | tasks (this phase) |
|---|---|---|
| tests | 24 | 21 |
| against the **fixed** service | 24 passed | **21 passed** |
| against the **pre-fix** service (restored from `git show HEAD:…`, then reverted) | 17 failed | **12 failed** |

The 12 failures are exactly the security assertions — list, read-by-id, update
(three shapes), delete, create (two shapes), non-disclosure body equality, and the
teeth check. The 9 that pass in both states are the legitimate-path controls, which
is what makes them controls rather than assertions of the fix.

Every write test snapshots row count **and** a full serialization of every row
before the attempt and re-compares after, so a write that happened and then reported
a denial still fails. The cross-tenant case asserts the stronger property directly:
after an update carrying a foreign `organizationId`, the row's tenant column is
still `ORG_1` and no row anywhere in the table belongs to `ORG_2`.

## 7. The Spring side — verified, not assumed

Searched `backend/**/src/main` for every `where … projectId` and every raw
`:projectId` / `:project` binding.

**No live instance of the pattern.** The reason is structural: `CrmQueryScope` has
**no public constructor**, so a scope can only come from `forProject`,
`accessibleProjects`, or `tenant`, and `forProject` always calls
`TenantQueryGuard.scope()` first, which refuses a project outside the principal's
server-resolved list before SQL is assembled. An empty project set emits `1 = 0`
rather than the invalid `in ()`. Every CRM statement routes through it.

Two **structural** observations — safe today, but the signature does not enforce what
`CrmQueryScope` enforces:

- `channels/…/ChannelConnectionRepository.findEnabled(UUID organizationId, UUID
  projectId, ChannelType)` takes a raw project UUID and binds `:project`. Its only
  production caller is `OutboundDeliveryService:128`, which passes
  `context.organizationId()` and `message.projectId()` — both server-derived. Safe
  today; a future caller could pass a request value.
- `reporting-finance/…/RollupRepository` binds `:projectId` alongside the reserved
  `__fe_tenant_organization_id`. The organization comes from a validated scope, so a
  cross-tenant read is impossible and the project predicate only narrows.

Neither is exploitable. Both are the kind of thing a guard rule should catch, which
is the argument for §8.

## 8. Recommended guard rule (for the orchestrator to decide)

The class is not "someone forgot a check" — it is **"the shared guard has a
conditional contract and the code assumed it was unconditional."** Two rules would
have caught every site in this sweep:

1. **ArchUnit / lint, Spring side:** a persistence class may not accept a raw
   project `UUID`; it must accept a `TenantScope` or `CrmQueryScope`. That closes the
   two structural gaps in §7 and makes the safe path the only path, as it already is
   for `CrmQueryScope`.
2. **ArchUnit / lint, legacy side:** a controller may not pass a raw
   `ctx`-derived scope followed by `...body` (the spread must come first), and
   `@RequirePermissions` must be present on every non-`@Public()` handler. `ClinicController`
   currently has neither.

## 9. Files changed

| Path | Change |
|---|---|
| `apps/api/src/tasks/tasks.service.ts` | +27 / −8 — `resolveProject`, `getById`, `create`, `update`, `assertOwned` |
| `backend/crm-core/src/test/legacy-notes-project-scope/tasks-project-scope.test.ts` | new — 21 tests |
| `backend/crm-core/src/test/legacy-notes-project-scope/validation-pipe-reachability.test.ts` | new — 4 tests |
| `backend/crm-core/src/test/legacy-notes-project-scope/legacy-harness.ts` | new — shared `where` matcher and exception-filter renderer |
| `backend/crm-core/src/test/legacy-notes-project-scope/notes-project-scope.test.ts` | refactored onto the shared module; same 24 tests, still green |
| `backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts` | decorator metadata for Nest instantiation |

**Not touched:** `apps/api/src/notifications/notifications.service.ts`,
`apps/api/src/clinic/local-clinic.adapter.ts` (both assessed, both left alone),
every other `apps/**` file, all `backend/*` main sources, `packages/**`,
`ops/flyway/**`, `tests/contracts/**`, `migration-ledger.md`.

## 10. Commands

```powershell
# the proof — 49 tests across three suites, no database required
npx vitest run --config backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts

# the helpers these fixes depend on (unit, no database)
npx vitest run packages/database/src/tenant.test.ts

# the legacy API typechecks and lints clean
npx tsc --noEmit -p apps/api/tsconfig.json
npx eslint apps/api/src/tasks/tasks.service.ts apps/api/src/notes/notes.service.ts

# the Spring side is unchanged and still correct, against real PostgreSQL
$env:JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
cmd /c 'gradlew.bat -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true" :backend:crm-core:test --tests "com.firstelement.crm.query.CrmQueryScopeTest" --tests "com.firstelement.crm.CrmCoreIntegrationTest.projectNoteListIsScoped" --console=plain'
```

The legacy `apps/api/test/*.integration.test.ts` suites were again **not** run: they
write to the shared `firstelement` database, which this phase was told never to
modify.

## 11. Residual risk

- **The clinic cluster is live and cross-tenant.** Any authenticated principal, with
  no permission requirement, can read, rewrite or delete doctors, procedures and
  appointments in any tenant. This is more severe than the defect this mission was
  opened for, and it is open.
- **The harness uses an in-memory Prisma stand-in** for both services. It implements
  only the operators emitted (scalar equality, `{ in: [...] }`) and ignores
  `select`. A real-database run of the legacy tasks and notes services is still owed.
- **The sweep is complete for `apps/api/src` as of this commit** and was read site by
  site, but `apps/worker` and `packages/*` were not swept in this mission.
