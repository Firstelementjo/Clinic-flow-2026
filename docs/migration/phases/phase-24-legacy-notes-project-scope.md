# Phase 24 — legacy notes: project membership was never enforced (P0)

Status: **fixed and verified**. One production file, one new harness of 24 tests.
No migration. No permission change. The Spring implementation was already correct
and was not touched.

| | |
|---|---|
| Defect | `scopedWhere` pinned a caller-supplied `projectId` verbatim — `{ organizationId: ctx.organizationId, projectId }` — so project **membership** was never checked |
| Consequence | any authenticated principal in an organization holding `NOTE_READ` / `NOTE_UPDATE` / `NOTE_DELETE` could read and rewrite and delete notes in **any project in that same organization**, given only that project's id |
| Blast radius | same tenant, wrong project. `organizationId` is server-derived, so this could **not** cross a tenant |
| Production change | `apps/api/src/notes/notes.service.ts` — `scopedWhere` and `create` only (+29 / −9) |
| Verification | `backend/crm-core/src/test/legacy-notes-project-scope/` — 24 tests. Against the pre-fix code, **17 of the 24 fail** |
| Migration impact | **none** |
| Frozen-tree rule | overridden for this defect only, by the orchestrator, and recorded in the ledger |
| Spring side | unchanged; `CrmQueryScope.forProject` already validated through `TenantQueryGuard` |

---

## 1. What was wrong

`apps/api/src/notes/notes.service.ts`, before:

```ts
private scopedWhere(ctx: TenantContext, projectId: string | undefined, extra: Record<string, unknown>) {
  const base = projectId
    ? { organizationId: ctx.organizationId, projectId }   // caller-supplied, verbatim
    : { ...organizationScope(ctx) };
  return { ...base, ...extra };
}
```

`organizationId` comes from the session, never the request, so the query could not
cross an organization. `projectId` came straight off the wire with **no membership
check at all**.

The route did not compensate:

```ts
// notes.controller.ts — before
@UseGuards(AuthGuard)
@Get('project')
@RequirePermissions(Permission.NOTE_READ)
async listProject(@Tenant() ctx, @Query() query: { projectId: string }) {
  return this.notesService.listProject(ctx, query.projectId);
}
```

`Permission.NOTE_READ` was enforced. Project membership was not, and no guard
anywhere in `apps/api` checks membership — `AuthGuard` resolves `ctx.projectIds`
from the session and nothing ever consumes them on this path.

### The comment was part of the defect

The helper's Javadoc claimed the boundary it did not have:

> *otherwise we bound reads to the organization (**the route-level Permission guard
> enforces membership**)*

That sentence is false, and it is the sentence a future reader would trust. It has
been replaced with a description of the boundary that now exists, including the
explicit statement that the route guards enforce the *permission* and nothing else.

## 2. Every route the helper reaches, and its state before and after

`scopedWhere` is reached by `listForResource`, `listProject`, and `assertOwned`;
`assertOwned` is the only gate in front of `update` and `delete`, and both
mutations then run on `where: { id }` with no scope predicate of their own. So the
entire write boundary of this module was one helper call.

| # | Route | Reaches `scopedWhere` | Before | After |
|---|---|---|---|---|
| 1 | `GET /notes/project?projectId=` | yes, pinned | **OPEN** — read any project in the org | **closed** — 404 |
| 2 | `GET /notes?…&projectId=` | yes, pinned | **OPEN** — read any project in the org | **closed** — 404 |
| 3 | `GET /notes?…` *(no `projectId`)* | yes, unpinned branch | **OPEN** — read every project in the org | **closed** — 404 |
| 4 | `PATCH /notes/:id` *(pinned)* | via `assertOwned` | **OPEN** — rewrote another project's note body | **closed** — 404 |
| 5 | `PATCH /notes/:id` *(no `projectId`)* | via `assertOwned` | **OPEN** — rewrote another project's note body | **closed** — 404 |
| 6 | `DELETE /notes/:id` *(pinned)* | via `assertOwned` | **OPEN** — deleted another project's note | **closed** — 404 |
| 7 | `DELETE /notes/:id` *(no `projectId`)* | via `assertOwned` | **OPEN** — deleted another project's note | **closed** — 404 |
| 8 | `POST /notes` | **no** — pins `body.projectId` directly | **OPEN** — inserted a note into another project | **closed** — 404 |

Route 8 does not reach the helper and was not in the reported diagnosis. It was
found while enumerating the pinned-`projectId` call sites, it is the same defect
class, it is the same fix (`projectScope`), and the Spring port already refuses it
(`NoteService.create` → `CrmQueryScope.forProject`). It is included rather than left
open and undisclosed. It is called out here because it is the one item in this
phase that widens the reported scope, and a security owner should see it as a
decision rather than discover it later.

### Yes — update and delete could cross a project boundary

Confirmed, by tracing and then by execution. Two distinct shapes reached it:

- **Pinned.** `PATCH /notes/:id` with `projectId` set to another project's id
  passed `assertOwned` — the predicate `{ organizationId: <mine>, projectId:
  <theirs>, id }` matches a note in *their* project — and then
  `note.update({ where: { id }, data: { body } })` rewrote it. Delete likewise.
- **Unpinned.** `projectId` is optional on both routes (`@Body() body: { body:
  string; projectId?: string }` and `@Query() query: { projectId?: string }`).
  Omitting it took the `organizationScope` branch, which filters on the
  organization alone, so the lookup found the foreign note by id and the write
  proceeded. This is the worse shape: the note id alone is sufficient.

Route 3's unpinned read has the same root cause: an employee whose membership list
is empty received every note in the tenant, which FR-1.4 forbids outright.

## 3. The change

```ts
// apps/api/src/notes/notes.service.ts — after
const base = projectId ? projectScope(ctx, projectId) : accessibleProjectsScope(ctx);
```

and in `create`, the project comes from the checked scope rather than the body:

```ts
const scope = projectScope(ctx, projectId);
// ... organizationId: scope.organizationId, projectId: scope.projectId
```

Both helpers already existed in `packages/database/src/tenant.ts` and were already
the convention in this codebase — `customers`, `conversations`, `cases`,
`knowledge`, `channels`, `escalation`, `ai/agents`, `projects` and
`analytics` all scope through them. Notes was the module that was left behind. No
new abstraction was introduced and **nothing outside `apps/api/src/notes/**` was
modified**; the helpers were imported, not edited.

### Why the answer is 404 and not 403

`projectScope` throws `TenantAccessError`, and the global `HttpExceptionFilter`
already renders it deliberately:

```ts
// apps/api/src/common/http-exception.filter.ts — unchanged
if (exception instanceof TenantAccessError || exception instanceof PermissionDeniedError) {
  this.logger.warn({ message: 'Access denied', requestId, userId, path, reason });
  return this.send(response, HttpStatus.NOT_FOUND, {
    code: ErrorCode.NOT_FOUND, message: 'Resource not found', requestId,
  });
}
```

> *Tenant and permission failures are deliberately reported as 404, not 403:
> telling a caller "this project exists but is not yours" is itself a
> cross-tenant information leak.*

No controller change was needed, and none was made. `plan.md` §0.4 forbids
controller-only authorization, so membership belongs in the service; adding a
redundant project guard to the controller would have widened the diff without
adding a check. The real reason is logged server-side and never reaches the client.

## 4. Non-disclosure, and how it was verified

The harness does not assert "some error was thrown". It invokes the **real**
`HttpExceptionFilter` on the **real** exception the service throws, captures the
status and body a client would receive, and compares that body byte-for-byte
against the body produced for a project id that exists nowhere:

| Attempt | Status | Body |
|---|---|---|
| colleague, another project in the same org | 404 | `{ code: 'NOT_FOUND', message: 'Resource not found', requestId }` |
| cross-tenant project id | 404 | *identical* |
| a project id that does not exist | 404 | *identical* |

Three further assertions make this load-bearing rather than decorative:

- the denied body is asserted **not to contain** the project id, the author's name,
  or any note body;
- `requestId` is stripped and the remaining bodies compared, so the one field that
  legitimately varies per request is excluded rather than relied upon — and a
  dedicated test asserts that `requestId` is the *only* difference between two
  otherwise identical denials;
- every denial is asserted to raise **before any query ran**
  (`prisma.calls` is empty), so a denial cannot be a post-filter over rows that
  were already read.

## 5. "Nothing changed" is the load-bearing assertion

A denial test that only checks for an error passes even when the write happened and
then reported the error. Every write scenario therefore snapshots the entire table
— row count **and** a full serialization of every row — before the attempt and
re-compares it after:

```ts
const rowsBefore = prisma.count();
const stateBefore = prisma.snapshot();

const outcome = await httpOutcome(() => notes.update(COLLEAGUE_B, noteInA, 'overwritten', PROJECT_A));

expect(outcome.status).toBe(404);
expect(prisma.count()).toBe(rowsBefore);      // 3 rows before, 3 rows after
expect(prisma.snapshot()).toBe(stateBefore);  // every field of every row identical
expect(prisma.row(noteInA)?.body).toBe('alpha private body');
expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
```

Covered for **update** (pinned, unpinned, cross-tenant), **delete** (pinned,
unpinned, cross-tenant), and **create** (same-org, cross-tenant).

## 6. No regression on the legitimate paths

- A member reads their own project — 2 rows, correct bodies.
- A member reads notes for a record in their own project, pinned and unpinned.
- A member can still update and delete their own project's note.
- A member can still create a note in their own project.
- **An org admin still reads and writes every project in their organization.**
  `SessionService.resolve` hands an `ADMIN` every project in the org, so the fixed
  predicate accepts all of them; the harness mirrors that resolution and asserts
  the update genuinely mutated the row, not just that it was permitted.
- A principal with **no** membership now receives nothing (FR-1.4). Pre-fix it
  received the whole tenant.

## 7. Red/green proof

A green suite proves nothing unless it can go red. The pre-fix file was restored
from `git show HEAD:apps/api/src/notes/notes.service.ts`, the suite re-run, and the
fix restored:

| | |
|---|---|
| against the fixed service | **24 passed, 0 failed** |
| against the pre-fix service | **7 passed, 17 failed** |

The 17 failures are exactly routes 1–8 above plus the two non-membership variants,
which is the route inventory in §2 turned into executed evidence. The suite also
carries a permanent teeth check: it reproduces the pre-fix predicate inline and
asserts it *does* return the fixture's foreign notes and *does* find the foreign
note on the write path, so a future green run cannot be explained by a blind
fixture.

One finding from this pass is worth recording because it was mine and not the
service's: three of my initial assertions expected the unpinned write path to throw
`TenantAccessError` before querying. It does not — `accessibleProjectsScope` runs
one bounded `findFirst` that cannot match a note outside the caller's projects. The
security outcome is the same (404, no write), and the assertions now check the real
mechanism, which is stronger: they assert the exact
`{ organizationId, projectId: { in: [...] }, id }` predicate, proving the boundary
is the caller's project set.

## 8. Files changed

| Path | Change |
|---|---|
| `apps/api/src/notes/notes.service.ts` | +29 / −9 — `scopedWhere`, `create`, and the false Javadoc |
| `backend/crm-core/src/test/legacy-notes-project-scope/notes-project-scope.test.ts` | new — 24 tests |
| `backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts` | new — standalone runner |

Nothing else. `apps/api/src/notes/notes.controller.ts`,
`notes.module.ts`, `packages/database`, `ops/flyway`, `tests/contracts` and the
migration ledger were not touched. The Spring modules were not touched.

### Why the harness lives here

`apps/**` is the frozen legacy reference, so the suite cannot go in
`apps/api/test/`. The root `vitest.config.ts` includes only `packages/**` and
`apps/api/test/**`, so the harness is a standalone config under this module's test
tree — a permitted location — and it **imports the real legacy service**, it does
not copy it. Only `Java` sources are compiled by the Gradle test source set, so
these two files are invisible to the build.

## 9. Commands

```powershell
# the proof — 24 tests, standalone config, no database required
npx vitest run --config backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts

# the helpers this fix depends on (unit, no database)
npx vitest run packages/database/src/tenant.test.ts

# the legacy API typechecks and lints clean
npx tsc --noEmit -p apps/api/tsconfig.json
npx eslint apps/api/src/notes/notes.service.ts apps/api/src/notes/notes.controller.ts

# the Spring side is unchanged and still correct, against real PostgreSQL
$env:JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
cmd /c 'gradlew.bat -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true" :backend:crm-core:test --tests "com.firstelement.crm.query.CrmQueryScopeTest" --tests "com.firstelement.crm.CrmCoreIntegrationTest.projectNoteListIsScoped" --console=plain'
```

The legacy `apps/api/test/*.integration.test.ts` suites were deliberately **not**
run: they write to the shared `firstelement` database, which this phase was told
never to modify. The notes service is not referenced by any of them, and the
harness covers its scope without a database.

## 10. Residual risk

- **The harness uses an in-memory Prisma stand-in**, not PostgreSQL. It implements
  only the operators the notes service emits (scalar equality and `{ in: [...] }`)
  and ignores `select`. The assertions are about *which rows are reachable*, never
  about projection, so this is the right trade — but a real-database run of this
  service is still owed. The Spring equivalent already has one
  (`CrmCoreIntegrationTest.projectNoteListIsScoped`, passing).
- **Two 404 variants remain distinguishable in the body**, and deliberately so:
  `Resource not found` for a project outside the caller's scope, `Note not found`
  for a note not inside a project the caller *does* hold. Both are 404 and neither
  discloses cross-project existence — probing a foreign note requires naming the
  foreign project, which is refused before any lookup. This is pre-existing
  behaviour and was left alone rather than widened.
- **Other legacy modules may repeat this shape.** This phase fixed notes because
  that is what was reported. A repository-wide sweep for
  `{ organizationId: ctx.organizationId, projectId }` and for
  `organizationScope(ctx)` on project-scoped tables is a follow-up; notes was
  found by reading, not by a scanner, so absence of evidence here is not evidence
  of absence.

## 11. Sweep for the same shape (read-only, nothing fixed)

Run while this phase was open, so the follow-up is a short list rather than a
hypothesis. Two pattern passes over `apps/api/src`, then each hit read by hand.

**No other read path is affected.** Every module that touches a project-scoped
table now scopes through one of the three sanctioned helpers — `projectScope`,
`accessibleProjectsScope`, or `canAccessProject`. Notes was the only module that
used none of them.

Two apparent hits resolve as **correct on inspection**, and are recorded here so
nobody re-opens them:

- `views/views.service.ts:93` returns `{ organizationId: ctx.organizationId, projectId }`
  — but line 90 already calls `canAccessProject` and throws a 404 first, and line
  128 re-checks the row's own project. Correct.
- `accounts/accounts.service.ts` uses `organizationScope(ctx)` on four sites — but
  the `Account` model has **no `projectId` column**. It is organization-scoped by
  design, so there is no project boundary there to enforce.
- `organizations/organizations.controller.ts` scopes to the organization itself.
  Correct.

**One write path is a live sibling of the notes `create` gap, and is NOT fixed
here** — it is outside this phase's ownership globs:

```ts
// apps/api/src/tasks/tasks.service.ts:109
projectId: input.projectId ?? this.defaultProjectId(ctx),
```

`input.projectId` reaches the row with no membership check, so a caller with
`TASK_CREATE` can create a task inside a project they are not a member of — the
same defect as notes route 8, on a different module. `defaultProjectId` is safe
(it returns `ctx.projectIds[0]`); the `input.projectId` branch is not. The same
shape was observed at `clinic/local-clinic.adapter.ts:302` and
`notifications/notifications.service.ts:109` and has **not** been assessed.

Recommended follow-up, in priority order: sweep every `create`/`insert` in
`apps/api/src` that assigns a caller-supplied `projectId`, and route all of them
through `projectScope` as this phase did for notes.

