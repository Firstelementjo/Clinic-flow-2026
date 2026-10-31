# Phase 33 — clinic permissions: closing the role gap the tenant fix left open (P0)

Status: **closed.** 18 new permissions in `packages/auth`, 21 decorators on
`apps/api/src/clinic/**`, 45 new tests. No migration. No Spring change.

| | |
|---|---|
| Trigger | Phase 26 closed a cross-tenant breach on the clinic surface and reported the rest: `Permission` had no clinic members and `ClinicController` declared no `@RequirePermissions` |
| Severity | **Missing role authorization** on clinical data. Tenant-confined, not authorized — any employee of a tenant could read clinician contact details, rewrite or delete the roster, cancel a patient's appointment, and drive the AI simulator |
| Root cause | Same as phase 26's first finding, one layer up: nothing in the request path asked *who*, only *which tenant* |
| Production change | `packages/auth/src/policy.ts` (+18 members, +2 exported grant sets); `apps/api/src/clinic/clinic.controller.ts` (21 decorators); `local-clinic.adapter.ts`, `clinic.service.ts`, `reminders.service.ts` (**message strings only**); `public-appointment.controller.ts` (comment only) |
| Verification | `clinic-permissions.test.ts` — 45 tests, through a real Nest app with the real `AuthGuard`, `PermissionsGuard` and `HttpExceptionFilter` |
| Migration impact | **none** |
| The refusal | **404, unchanged.** `PermissionDeniedError` already rendered as `404 {code: NOT_FOUND, message: 'Resource not found'}`; proven byte-identical to a nonexistent row on every addressed route |
| Reported, not fixed | worker `DocumentProcessingJob`, three `console.log` in `inbound-pipeline.ts`, seed password log (§8) |

---

## 1. The gap, precisely

Phase 26 made the clinic surface **tenant-confined**. It did not make it
**authorized**. Concretely, before this phase:

- `GET /clinic/doctors/manage` returned clinician `email` and `phone` plus
  appointment counts, to anyone with a session.
- `PATCH`/`DELETE /clinic/doctors/:id` and `PATCH`/`DELETE /clinic/procedures/:id`
  rewrote and deleted the clinical roster, to anyone with a session.
- `POST /clinic/appointments/:id/cancel` cancelled a patient's appointment.
- `POST /clinic/ai-simulator/chat` **created appointments** through an
  unreviewed model, with no permission check and no consent record.
- `POST /clinic/reminders/send-batch` messaged patients.

Every one of those required only: a valid session cookie, and membership of the
caller's own tenant. A support agent and a clinic owner were the same principal.

Phase 26 declined to fill this by borrowing an unrelated permission, on the grounds
that a mislabelled grant is how a boundary comes to be trusted when it was never
checked. That reasoning stands. This phase supplies the real members.

## 2. The permission set, and the route each maps to

Derived from the routes that exist, not from a wish list. Every row below is
asserted in `clinic-permissions.test.ts` against the controller's own reflected
metadata.

| Route | Permission | Why this name |
|---|---|---|
| `GET /clinic/doctors` | `doctor:read` | Name, specialty, department, working hours — what a booking form needs |
| `GET /clinic/doctors/manage` | `doctor:read-management` | Adds **contact details and an appointment count**. A different disclosure, so a different grant: collapsing it into `doctor:read` would put emails and phones behind the booking-form permission |
| `POST /clinic/doctors` | `doctor:create` | |
| `PATCH /clinic/doctors/:id` | `doctor:update` | |
| `DELETE /clinic/doctors/:id` | `doctor:delete` | |
| `GET /clinic/procedures` | `procedure:read` | Includes price |
| `GET /clinic/procedures/manage` | `procedure:read-management` | Adds the appointment count per procedure |
| `POST /clinic/procedures` | `procedure:create` | |
| `PATCH /clinic/procedures/:id` | `procedure:update` | |
| `DELETE /clinic/procedures/:id` | `procedure:delete` | |
| `GET /clinic/procedures/:id/duration` | `procedure:read` | One field of the same resource; a separate permission for it would be a distinction with no decision behind it |
| `GET /clinic/slots` | `schedule:read-availability` | Reveals working days/hours and current occupancy. Named for the capability, not folded into the roster read |
| `GET /clinic/check-availability` | `schedule:read-availability` | Same capability, same disclosure |
| `POST /clinic/appointments` | `appointment:create` | |
| `PATCH /clinic/appointments/:id/reschedule` | `appointment:reschedule` | Moves the row, returns it to `PENDING` |
| `POST /clinic/appointments/:id/cancel` | `appointment:cancel` | **Not** an update: releases the slot, appends a cancellation reason to the patient's notes, and is what the reminder chain reacts to. Separate grants because the blast radius is |
| `POST /clinic/ai-simulator/chat` | `clinic:simulate-ai` **+** `appointment:create` | See §3 |
| `GET /clinic/reminders` | `reminder:read` | Returns patient names and phone numbers |
| `POST /clinic/reminders/send-batch` | `reminder:send` | Leaves the building: this is the route that messages a patient |
| `POST /clinic/reminders/send/:appointmentId` | `reminder:send` | Same capability |
| `POST /clinic/reminders/respond` | `reminder:record-response` | Staff recording a patient's answer, which writes `CONFIRMED` / `RESCHEDULE_REQUESTED` / `CANCELLED` onto an appointment |
| `GET /clinic/analytics` | `analytics:read-project` | Pre-existing and already honest |

Two naming decisions worth stating plainly, because both were available and both
were rejected:

- **`clinic:read` was rejected.** Phase 26 §6 suggested one coarse read member for
  the roster, catalog, slots and availability. That name covers four different
  disclosures across three record types, which is exactly the mislabelling ADR 0010
  §2 exists to prevent.
- **`appointment:write` was rejected**, for the reschedule/cancel reason above.

## 3. The simulator carries two permissions, deliberately

`POST /clinic/ai-simulator/chat` reads the roster and catalog and, on a booking
intent, **calls `clinicService.createAppointment`**. Gating it on `clinic:simulate-ai`
alone would produce an audit record saying a user "simulated AI" while it wrote to the
schedule. So:

```ts
@RequirePermissions(Permission.CLINIC_AI_SIMULATE, Permission.APPOINTMENT_CREATE)
```

Both are required (`PermissionsGuard` loops with `assertCan`, so it is AND). This is
the only route on the surface with more than one, and it is asserted directly in
`gates the simulator on booking as well as simulating`.

## 4. Who holds what

`ADMIN` holds everything by construction — `ROLE_PERMISSIONS[ADMIN]` is
`Object.values(Permission)`, so every new member is included without an explicit
grant and cannot be forgotten.

`EMPLOYEE` gets `CLINIC_EMPLOYEE_GRANT`:

```
doctor:read              procedure:read          schedule:read-availability
appointment:create       appointment:reschedule  appointment:cancel
reminder:read            reminder:send           reminder:record-response
```

**Why these nine and not none.** The task brief's safe default is that an `EMPLOYEE`
holds nothing, and for a roster that default would be right. But this surface is a
**front desk**, and the nine above are the whole job: read the bookable roster, read
the catalog, check a slot, book, reschedule, cancel, work the reminders. An employee
who cannot book an appointment cannot work, so denying all of it is not a safe
default here — it is an outage wearing a security costume. What was open before this
phase was strictly larger than this set, so every employee is now **narrowed**, never
widened.

**What `CLINIC_ADMIN_ONLY` withholds from `EMPLOYEE`, and why:**

- `doctor:read-management`, `procedure:read-management` — clinician email, phone and
  workload aggregates. Contact data for a role that does not administer the roster.
- `doctor:create/update/delete`, `procedure:create/update/delete` — **every** roster
  mutation. A booking clerk edits slots; a clinician leaving the practice is a
  clinic-configuration decision with clinical consequences, and deleting a doctor
  whose appointments still exist is not self-service.
- `clinic:simulate-ai` — an unreviewed model writing to a clinical schedule should be
  an explicit opt-in, not a side effect of being an employee.

**No clinic role or capability is proposed, and here is the reasoning.** A
`CLINIC_STAFF` role would be the natural thing to add, and I am not adding it
silently: `UserRole` is a **Prisma enum** (`packages/database/prisma/schema.prisma`)
mirrored in `packages/shared/src/enums.ts`, and a third member is a schema migration
plus a matrix change — outside this phase's ownership and a decision the orchestrator
should make with the clinic's real staffing model in hand. The permission granularity
in §2 is deliberately shaped so that adding `CLINIC_STAFF` later is a **matrix edit
only**, with no route or decorator change: the names are already the ones that role
would need.

**A gap worth naming.** `UserRole` has exactly two values, so "a receptionist who may
book but not administer the roster" is currently expressible only as "EMPLOYEE" or
"ADMIN". The permission set anticipates the missing middle; the role enum does not
supply it.

## 5. The refusal is a 404, and it stays one

**Choice: 404, because that is what already happens and changing it would be a
change in disclosure.**

`PermissionsGuard` raises `PermissionDeniedError`; the global `HttpExceptionFilter`
renders that as:

```
404 { code: 'NOT_FOUND', message: 'Resource not found', requestId }
```

— the same status and body the adapter's `NotFoundException` produces for a row the
caller cannot reach. So the decorator inherits non-disclosure for free, and a 403 here
would have *introduced* an oracle: on a surface holding patient names and phone
numbers, "this exists and you may not have it" is exactly the information a caller
should not be able to extract by probing.

Two supporting changes were needed to make the shape **uniform**, because phase 26
left the adapter's messages naming the resource:

| Before | After | Why |
|---|---|---|
| `404 'Doctor not found'` | `404 'Resource not found'` | A per-resource message is a per-resource oracle: it distinguishes "no such doctor anywhere" from "that doctor is not yours" from "you may not ask" |
| `404 'Appointment not found'` | `404 'Resource not found'` | Same, in `clinic.service.ts` and `reminders.service.ts` |

Three assertions hold the line, and all three are in the suite:

- `is byte-identical to a row that does not exist, on every addressed route` — for
  each of the seven addressed routes, compares the permission-denial body to the
  body for a nonexistent id, **byte for byte including `message`**. Only the
  correlation id differs, and it differs on every request by design.
- `is uniform across the surface` — collects the refusal body from all 22 routes and
  asserts there is exactly **one** distinct shape.
- `never answers 403 on this surface` — the belt-and-braces version, so a future
  change that special-cases the clinic into a 403 fails even if the other two are
  weakened.

**The real reason is logged, not returned.** `HttpExceptionFilter` already logs
`reason: exception.message` server-side for both error types, so operators keep the
diagnostic and callers lose the oracle.

### Two routes deliberately do not 404, and neither discloses

- `GET /clinic/procedures/:id/duration` returns `{ durationMinutes: 30 }` for an
  unreachable procedure, because `getProcedureDuration` substitutes a default. A
  nonexistent procedure yields the same default, so the two are indistinguishable.
  Reported rather than changed — it is a service-level default, not an authorization
  decision, and changing it would alter a working response.
- `POST /clinic/ai-simulator/chat` answers with a natural-language reply. Its denial
  is a 404 like everything else; the reply is the success shape.

## 6. The `@Public()` reminder routes: unchanged, and asserted

`PublicAppointmentController` still has exactly two handlers, both `@Public()` at the
class level, both resolving **token-only**:

- `GET /public/appointments/:token` → `getPublicAppointmentDetails(token)`
- `POST /public/appointments/:token/respond` → `processPatientResponseByToken(...)`

Four tests hold this, and one of them is a control rather than a restatement:

- `are the only @Public() handlers in the module` — asserts the reflected route list
  is exactly those two, that both are public, and that **neither declares a
  permission**. A patient is not a principal; inventing one would be fiction.
- `serves a real token to an unauthenticated caller, as before` — no cookie at all,
  and it works.
- `refuses a wrong token, and a bare appointment id, with the same 404` — the id
  fallback phase 26 removed is still gone.
- `still refuses a bare appointment id on the respond route, unauthenticated` — and
  the appointment's status is asserted unchanged.

The only edit to `public-appointment.controller.ts` is the comment above it, which
now says *why* it carries no permission, so the next reader does not "fix" it.

## 7. Red-to-green

**Suite:** `clinic-permissions.test.ts`, 45 tests.

| Run | Result |
|---|---|
| Fixed code | **45 passed**, 0 failed |
| **Decorators stripped** from `clinic.controller.ts` (all 21 lines, file otherwise unchanged) | **28 failed**, 17 passed |
| Restored | **45 passed** |

Failing groups, verbatim from the red run — this is the point of the exercise, since
a suite that has only ever been green proves nothing, and in this area the previous
suite was green while the surface was open to every tenant:

- `declares the exact permission the contract names, on every handler`
- `gates the simulator on booking as well as simulating`
- `refuses an EMPLOYEE on every ADMIN-only route, and allows the front desk`
- all 21 generated `a principal without the permission is refused on every clinic route > ROUTE → permission` cases
- `is byte-identical to a row that does not exist, on every addressed route`
- `is uniform across the surface: every refusal is a 404 with one body shape`
- `a role that lacks the permission is refused by the guard, not by the adapter`

The 17 that stayed green are the ones that *should* stay green: the `@Public()`
token tests (untouched by a decorator on the other controller), the ADMIN-passes
test, the cross-tenant tests (phase 26's work, unaffected), and the body-ordering
tests.

**The reflection test is the durable part.** It does not check a list; it reads the
controller's metadata through the same `Reflector` the guard uses and compares it to
the contract table. Add a handler next month with no `@RequirePermissions` and it
fails naming the route, rather than shipping open:

```ts
expect(routes.length).toBeGreaterThan(15);                              // control: not zero
expect(routes.map((r) => r.route).sort())
  .toEqual(Object.keys(ROUTE_PERMISSIONS).sort());                     // new route appears here
```

### How "a principal without the permission" is produced, and why that is honest

`UserRole` has two values and both are fully specified in production, so several
clinic permissions are held by **every** principal that can authenticate — there is no
shipped principal who lacks `doctor:read`. `withoutPermission(permission, fn)`
therefore reassigns `ROLE_PERMISSIONS[EMPLOYEE]` to a list minus that permission for
the duration of one call and restores it in a `finally`.

It substitutes **a role table and nothing else**. `PermissionsGuard`, `assertCan`,
`can`, `HttpExceptionFilter`, the controller, the services and the adapter are all the
production ones. The matrix is checked intact in `afterEach` against the value
captured at module load, so a throwing test cannot leak a narrowed matrix forward.

And the fixture is proven able to detect the defect, because a scoping test that
cannot reach its rows passes for the wrong reason:

- `reaches every clinic row` — the roster, the management view's email, the reminder
  summary's patient name, and a permitted write that really does insert a row.
- `a role that lacks the permission is refused by the guard, not by the adapter` —
  asserts `prisma.calls` is **empty**, so the refusal provably happened before the
  handler ran rather than after a query returned nothing.

## 8. Reported, not fixed

Per instruction, all three are outside this phase's ownership.

1. **`apps/worker/src/main.ts:29-33`** — the orchestrator cited
   `apps/worker/main.ts`; the file is at **`apps/worker/src/main.ts`**.
   `DocumentProcessingJob` declares `organizationId` and `projectId`; the handler at
   line 242 uses only `job.data.documentId` and calls `ingestion.process(...)`.
   Harmless today and safe *because* it ignores them — but it is the same
   false-signal shape as the two false security comments phase 26 found: a reader
   scanning the job type reasonably concludes the handler is tenant-scoped, and the
   conclusion is unearned. If a future change starts using `documentId` against a
   database query, there is no scope in the job to enforce, and nothing at the type
   would signal the omission. Worth either threading a `TenantScope` into
   `ingestion.process` or deleting the two fields.
2. **`packages/messaging/src/inbound-pipeline.ts:162, 164, 169`** — three
   unconditional `console.log` calls (`'LATEST CONVERSATION FOUND:'`,
   `'UPDATING TO OPEN'`, `'UPDATED CONVERSATION:'`). Log noise in the worker's happy
   path, not disclosure: ids and statuses only, no patient content, and they sit
   beside a `Logger`-based path that already exists in the same module.
3. **`packages/database/src/seed.ts:221`** — `console.log('Password :', DEMO_PASSWORD)`.
   Confirmed nothing imports that module outside its own dev entrypoint; local dev
   only. Still a plaintext credential to stdout, and stdout is the one sink that
   routinely lands in CI logs.

## 9. Commands

```powershell
npx tsc -b packages/auth                                    # exit 0 (rebuild the .d.ts apps/api resolves)
npx tsc --noEmit -p apps/api/tsconfig.json                  # exit 0
npx eslint apps/api/src/clinic packages/auth                # 4 errors, 13 warnings
npx eslint backend/crm-core/src/test/legacy-notes-project-scope/clinic-permissions*.ts
                                                          # 0 problems
npx vitest run --config backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts
                                                          # 5 files, 130 tests passed (85 pre-existing + 45 new)
npx vitest run packages/                                    # 18 files, 240 tests passed
```

The 4 eslint errors are **pre-existing and unchanged**: measured by stashing
`apps/api/src/clinic` and `packages/auth` and re-running — **17 problems (4 errors,
13 warnings) both before and after**, identical.

The Spring side was not touched and needs no verification: no `backend/**`
production or test source changed, only files under
`backend/crm-core/src/test/legacy-notes-project-scope/`, which are not compiled by
Gradle.

The `apps/api/test/*.integration.test.ts` suites were **not** run: they write to the
shared `firstelement` database, which this phase was told never to modify.

## 10. Files changed

| Path | Change |
|---|---|
| `packages/auth/src/policy.ts` | 18 clinic members; `CLINIC_EMPLOYEE_GRANT`, `CLINIC_ADMIN_ONLY` exported so the grant is testable |
| `apps/api/src/clinic/clinic.controller.ts` | 21 `@RequirePermissions` decorators; gap comment replaced with the authorization contract |
| `apps/api/src/clinic/local-clinic.adapter.ts` | **message strings only** — 7 refusals now say `Resource not found` |
| `apps/api/src/clinic/clinic.service.ts` | **message strings only** — 2 refusals |
| `apps/api/src/clinic/reminders.service.ts` | **message strings only** — 2 refusals on the authenticated path |
| `apps/api/src/clinic/public-appointment.controller.ts` | **comment only** — records why it carries no permission |
| `backend/crm-core/src/test/legacy-notes-project-scope/clinic-permissions-harness.ts` | new — real Nest app, in-memory Prisma, role-narrowing helper |
| `backend/crm-core/src/test/legacy-notes-project-scope/clinic-permissions.test.ts` | new — 45 tests |
| `docs/migration/phases/phase-33-clinic-permissions.md` | new — this file |

## 11. Residual risk

- **The harness uses an in-memory Prisma stand-in.** As in phase 26. A
  real-PostgreSQL run of these routes against seeded cross-tenant data is still owed;
  it would confirm Prisma's own query translation agrees with the fake, not discover
  new authorization logic.
- **Two roles cannot express "receptionist".** See §4. The permission set is ready for
  the middle role; the enum is not.
- **`ADMIN` is a superset of everything.** A clinic owner and a support agent with
  admin rights are the same principal. That is pre-existing and true of every module;
  the clinic surface makes it visible sooner because the blast radius is patient data.
- **Doctor and procedure writes are still unaudited.** Phase 26 §8 established that
  only the three appointment actions write an audit row; roster create/update/delete
  write none. This phase makes those writes permission-gated but does not make them
  traceable. Still owed.
- **No field-level redaction on the management views.** `GET /clinic/doctors/manage`
  is now a separate permission, which is the coarse version of field redaction: a
  principal who may see the roster may still see every field the view returns. Finer
  redaction is a Spring-side design question.
- **`{...ctxDerived, ...body}` remains forbidden but unenforced.** Every write in the
  adapter enumerates its fields, and the controller's two spreads put `...body` first
  with the server-derived project after it. Both correct. Nothing mechanically stops a
  future handler from reversing that order — only a review convention, which phase 26
  already showed is not sufficient on its own.