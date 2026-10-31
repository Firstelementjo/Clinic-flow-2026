# Phase 26 — legacy clinic and CRM scope: closing a live cross-tenant breach (P0)

Status: **the cross-tenant breach is closed.** 16 files, one of them the
authorization boundary's false documentation. 85 tests across four suites.
No migration. The Spring side was verified and not touched.

| | |
|---|---|
| Trigger | Phase 25 found the clinic cluster cross-**tenant** and stopped, because fixing it needed `clinic.controller.ts` |
| Severity | **Cross-tenant**, on clinical data. Any authenticated principal, any tenant, could read, rewrite and delete doctors, procedures and appointments |
| Root cause | `PermissionsGuard` scopes a project only from `request.params.projectId`; no clinic route carries one |
| Production change | 15 files under `apps/api/src/{clinic,appointments,deals,leads,analytics}/`, plus a **comment-only** change to `auth/permissions.guard.ts` |
| Verification | `clinic-project-scope.test.ts` — 35 tests. **8 of 8** of the signature-stable tests fail against pre-fix code |
| Migration impact | **none** |
| Guard runtime | **unchanged, mechanically proven** — executable lines compared against `HEAD`, 30 vs 30, identical |
| Authorization gap | **reported, not papered over**: `Permission` has no clinic members at all (§6) |
| `apps/worker`, `packages/**` | **still unswept** (§9) |

---

## 1. The breach, precisely

Three independent failures, all reachable by any authenticated principal:

**1. No tenant on the write paths.** `PATCH`/`DELETE /clinic/doctors/:id` and
`PATCH`/`DELETE /clinic/procedures/:id` had **no `@Tenant()` at all**, and the
adapter mutated on a bare id:

```ts
// before — local-clinic.adapter.ts
const existing = await this.prisma.client.doctor.findUnique({ where: { id: doctorId } });
return this.prisma.client.doctor.update({ where: { id: doctorId }, data: { ...data } });
```

No `organizationId`, no `projectId`. Any doctor in any tenant, by id.

**2. The body overrode the tenant.** `POST /clinic/doctors`:

```ts
// before — clinic.controller.ts
this.clinicAdapter.createDoctor({
  organizationId: ctx.organizationId,
  projectId: ctx.projectIds[0] || '',
  ...body,          // <-- after, so body.organizationId wins
});
```

A cross-tenant **write primitive**.

**3. An appointment id was treated as a capability.** `processPatientResponse` was
one method behind two different routes: the public one, where the caller presents an
unguessable `actionToken` (a legitimate capability), and the authenticated one, where
the caller presents a bare appointment UUID:

```ts
// before — reminders.service.ts
let targetApptId = appointmentIdOrToken;
// …
const isUuid = /^[0-9a-f]{8}-…/.test(targetApptId);
let appt = isUuid ? await this.prisma.client.appointment.findFirst({ where: { id: targetApptId } }) : null;
```

The UUID fallback also sat on the **public** read, so an unauthenticated caller who
learned an appointment id could read a patient's name, phone, time and doctor.

## 2. The fix: the tenant arrives with the caller

`ClinicSystemAdapter` now takes `TenantContext` as the first parameter of every
method, so an unscoped call is not expressible:

```ts
export interface ClinicSystemAdapter {
  getDoctors(ctx: TenantContext, requestedProjectId?: string): Promise<ClinicDoctor[]>;
  getDoctorById(ctx: TenantContext, doctorId: string): Promise<ClinicDoctor | null>;
  // … every method
}
```

In the adapter:

- `resolveProject(ctx, requestedProjectId?)` runs a supplied id through
  `projectScope`; with none, it uses `ctx.projectIds[0]`, which is membership-derived.
- `assertDoctorInScope` / `assertProcedureInScope` / `assertAppointmentInScope` prove
  the row is in `accessibleProjectsScope(ctx)` **before** any write.
- `organizationId` is always `ctx.organizationId`.
- **`updateDoctor` and `updateProcedure` enumerate their fields and never spread.**
  This is not a detail — see §5, where spreading `...data` left the hole open in my
  own first attempt.
- Roster reads carry `organizationId` as well as `projectId`, which they previously
  lacked entirely.

`ClinicService`, `SchedulingEngineService`, `AiSimulatorService` and
`RemindersService` all thread the context through. `PermissionsGuard` is untouched.

### Two callers, two entry points

```ts
processPatientResponseByToken(token, action, reason)        // public: capability
processPatientResponseForCaller(ctx, appointmentId, action, reason)  // authenticated: scoped
resolveReminderByToken(token)                                // never accepts an id
```

The shared status/notes logic moved into one private `applyPatientResponse`, so the
two paths cannot drift. The public token path is unchanged for a patient clicking
their link; the id path is gone from both the public read and the public respond.

## 3. Red-to-green

**Signature-stable modules** — `appointments`, `deals`, `leads`, `analytics`
restored from `git show HEAD:…`:

| | |
|---|---|
| against the **fixed** code | **85 passed** (4 suites) |
| against the **pre-fix** code | **8 of 8** targeted tests **failed** |

**The clinic module could not be run byte-for-byte pre-fix, and the reason matters.**
The fix changed the adapter's signatures, so pre-fix `getDoctors(projectId)` and
post-fix `getDoctors(ctx, projectId)` are different functions and the old file cannot
be called from the new tests at all. Rather than claim a red run I did not get, the
clinic suite proves non-vacuity at the predicate level: the exact pre-fix query shapes
are reproduced against the same fixture and shown **reaching** the rows, while the
fixed adapter refuses them and is asserted to emit a predicate carrying the tenant.

- `preFixByBareId(doctorA)` resolves a doctor in another tenant — pre-fix did this.
- `preFixProjectOnly(PROJECT_A)` returns the whole roster with no `organizationId` —
  pre-fix did this.
- The fixed adapter refuses both, and its emitted predicate is asserted to be
  `{ id, organizationId: ORG_1, projectId: { in: [PROJECT_A] } }`.

That is weaker than a compile-and-run red, and it is stated as weaker.

## 4. Load-bearing assertions

Every write test snapshots row count **and** a full serialisation of every clinic and
CRM table before the attempt and re-compares after, so a write that happened and then
reported a denial still fails. Cross-tenant tests additionally assert the tenant
column directly:

```ts
expect(prisma.doctor.map((d) => d.organizationId)).toEqual([ORG_1, ORG_1, ORG_1]);
expect(prisma.doctor.filter((d) => d.organizationId === ORG_2)).toHaveLength(0);
```

Non-disclosure is proven through the real `HttpExceptionFilter`, comparing the
rendered 404 body byte-for-byte against the body for a project that exists nowhere.

## 5. Two bugs my own first attempt introduced

Both were caught by the suite, and both are worth recording because the second is the
exact failure mode this whole mission is about.

1. **`createDoctor` with a non-member project.** I asserted the row would be created
   in the caller's project. It is not: `resolveProject` throws, so the create is
   **refused outright**. The test was wrong; refusal is the stronger outcome.

2. **`updateDoctor` still spread `...data`.** My comment claimed "only the mutable
   clinical fields are copied through" while the code did `data: { ...data }` — so a
   body carrying `organizationId` still moved a doctor between tenants, and the test
   caught it (`expected 'org-2' to be 'org-1'`). Fixed by enumerating every field.
   Prisma ignores `undefined`, so partial updates still work.

A false comment about a security boundary is itself a defect. That was the notes
Javadoc, then the guard Javadoc, and it would have been the third.

## 6. Authorization gap — reported, not filled

**`Permission` has no clinic members.** There is no `DOCTOR_*`, `PROCEDURE_*`,
`APPOINTMENT_*` or `REMINDER_*` anywhere in `packages/auth/src/policy.ts`. The clinic
routes previously declared **no `@RequirePermissions` at all**, and after this change
they still do not, because adding one would mean borrowing an unrelated permission —
and a mislabelled grant is exactly how a boundary comes to be trusted when it was
never checked.

Exactly one handler has an honest match and got it:
`GET /clinic/analytics` → `ANALYTICS_READ_PROJECT`.

**Required, and deliberately not invented** — each needs an enum member in
`packages/auth` plus a role-matrix decision (does `EMPLOYEE` get read? does
`EMPLOYEE` get write?):

| Handler | Suggested member |
|---|---|
| `GET /clinic/doctors`, `/procedures`, `/slots`, `/check-availability` | `CLINIC_READ` |
| `POST /clinic/doctors` | `DOCTOR_CREATE` |
| `PATCH`/`DELETE /clinic/doctors/:id` | `DOCTOR_UPDATE`, `DOCTOR_DELETE` |
| `POST /clinic/procedures` | `PROCEDURE_CREATE` |
| `PATCH`/`DELETE /clinic/procedures/:id` | `PROCEDURE_UPDATE`, `PROCEDURE_DELETE` |
| `POST /clinic/appointments`, reschedule, cancel | `APPOINTMENT_WRITE` |
| `POST /clinic/reminders/send-batch`, `/send/:id` | `REMINDER_SEND` |
| `POST /clinic/ai-simulator/chat` | `CLINIC_SIMULATE` |

Until those exist, **any authenticated session can still reach the clinic module** —
but now only its *own* tenant and projects. The breach is closed; the authorization
gap is reported.

## 7. The guard's false claim — truthfulness only

`apps/api/src/auth/permissions.guard.ts` said:

> *"if the route carries a `:projectId`, it is checked … **A handler therefore cannot
> forget to scope itself.**"*

That is false for every query/body route and it is what hid the class. The comment now
states the conditional contract, names the routes it does not cover, and explains why
the clinic module is the clearest case.

**Runtime behaviour is unchanged, and that is mechanically proven:** executable lines
were extracted from `HEAD` and from the working copy with comment lines filtered out —
**30 before, 30 after, `Compare-Object` reports no differences.**

Widening the guard to read `query.projectId` would change every existing route's
meaning at once. That is a security-owner decision, not a comment fix, and it is not
made here.

## 8. The incident questions

**Could an external attacker reach this?**

**A valid authenticated session was required** for every cross-tenant path.
`ClinicController` is `@UseGuards(AuthGuard)` with no `@Public()` on any handler, so
all of it needs a session cookie. Any employee or admin of *any* tenant sufficed;
no permission, no membership, no project access. The session cookie is
`HttpOnly`/`SameSite`, so this was not reachable by a drive-by web attacker — it
required an account.

One path did **not** require a session: the `@Public()` reminder routes accepted a
bare appointment **UUID** as well as the token. That is an information exposure
rather than a credential bypass, and a v4 UUID is not practically guessable — so I
will not describe it as "no session needed" beyond: *knowledge of an appointment id
was sufficient to read a patient's details unauthenticated.* That fallback is now
removed.

**Blast radius.**

- **Record types:** `doctors`, `procedures` (names, specialties, emails, phones,
  working hours, prices) and `appointments` (patient name, phone, email, notes,
  status, date) — plus `leads`, `deals`, and audit-log rows with actor names and
  emails.
- **Tenants:** all of them. The boundary that was missing is the *tenant* one.
- **Read:** any principal could enumerate any tenant's clinic roster and appointments.
- **Altered:** any principal could rewrite or delete any doctor, procedure or
  appointment in any tenant, could move a doctor into their own tenant via the body
  spread, and could cancel a patient's appointment.
- **Deleted:** doctors and procedures, by id, in any tenant.

**Was it plausibly exercised? — I cannot determine this, and here is why.**

The clinic module writes only **four** audit actions: `APPOINTMENT_CREATED`,
`APPOINTMENT_RESCHEDULED`, `APPOINTMENT_CANCELLED` and `REMINDER_SENT`. **Doctor and
procedure create/update/delete write no audit row at all**, and the
`processPatientResponse*` status change writes none either. Reads write none
anywhere. So for the most exposed write paths there is **no evidence trail**.

One partial, bounded lead exists for the three audited appointment actions: those
rows carry the *actor's* `organizationId` with the *target* appointment's id, so a
cross-tenant reschedule or cancel would leave a row whose organization disagrees with
the appointment it references. A join on `audit_log.resourceId = appointments.id`
where the organizations differ would surface them. Limits: it only covers three
actions, it needs the appointment row to still exist, and **deletes are not audited**,
so a deleted doctor or procedure leaves nothing at all.

What would be needed to answer properly, and is out of reach from here: database
access (which this phase was told never to touch), and application logs for the
window — the Nest logger records denials and errors, but a *successful* unauthorized
read or write produces no log line, so logs cannot answer it either.

**I am not claiming this was or was not exploited.** What the evidence supports is
that it was reachable by any account holder in any tenant, for the whole period the
legacy runtime has been serving.

## 9. The sweep — per-site verdict

Every row read in the code. "Same org" means `organizationId` was pinned server-side.

### Clinic — the severe cluster

| Site | Verdict | State |
|---|---|---|
| `local-clinic.adapter.ts` `getDoctors` / `getAllDoctors` | cross-tenant read | **fixed** — `organizationId` added, project via `projectScope` |
| `…` `getProcedures` / `getAllProcedures` | cross-tenant read | **fixed** |
| `…` `getDoctorById` / `getProcedureById` | cross-tenant read (`findUnique({id})`) | **fixed** — scoped `findFirst` |
| `…` `updateDoctor` | cross-tenant write **+** body-spread tenant move | **fixed** — scoped assert, fields enumerated |
| `…` `deleteDoctor` | cross-tenant delete | **fixed** — scoped `deleteMany` + count |
| `…` `createDoctor` / `createProcedure` | cross-tenant write via body spread | **fixed** — `{...body}` moved first, tenant from ctx |
| `…` `updateProcedure` / `deleteProcedure` | cross-tenant write/delete | **fixed** |
| `…` `getExistingAppointments`, `getDoctorSchedule` | cross-tenant read | **fixed** |
| `…` `createAppointment` / `rescheduleAppointment` / `cancelAppointment` | cross-tenant write | **fixed** |
| `clinic.controller.ts` `PATCH`/`DELETE doctors/:id`, `procedures/:id` | **no `@Tenant()`** | **fixed** — `@Tenant()` added |
| `clinic.controller.ts` `GET slots`, `check-availability`, `procedures/:id/duration` | no tenant | **fixed** — `@Tenant()` added |
| `clinic.controller.ts` `POST reminders/respond` | **no tenant, no permission** | **fixed** — scoped caller method |
| `reminders.service.ts:259,283` `processPatientResponse` | cross-tenant write | **fixed** — split into token + scoped caller |
| `reminders.service.ts:331` `getPublicAppointmentDetails` | unauthenticated read by id | **fixed** — token-only |
| `reminders.service.ts` `getReminderSummary`, `runBatchReminders`, `sendReminderForAppointment` | same-org, not project-scoped | **fixed** — `accessibleProjectsScope` |
| `clinic.service.ts` `rescheduleAppointment`, `cancelAppointment` | same-org, not project-scoped | **fixed** |
| `ai-simulator.service.ts:70,80-81` | cross-tenant read via `dto.projectId` | **fixed** — `projectScope` |
| `scheduling-engine.service.ts` (all 4 methods) | unscoped adapter calls | **fixed** — `ctx` threaded |

### CRM modules

| Site | Verdict | State |
|---|---|---|
| `appointments.service.ts:98` `list` | **ignored `filters.projectId` entirely** and returned the whole organization — patient names and phones | **fixed** |
| `appointments.service.ts:141` `create` | cross-project write | **fixed** |
| `appointments.service.ts:149` `customer.findUnique({id})` | **cross-tenant read** — copied another tenant's name/phone/email/ticket number | **fixed** |
| `appointments.service.ts:129,209,242` `getById`/`update`/`delete` | same-org, not project-scoped | **fixed** |
| `deals.service.ts:16,117` `list`/`create` | cross-project | **fixed** |
| `deals.service.ts:58,244` `getById`/`update` | same-org, not project-scoped | **fixed** |
| `leads.service.ts:16,133` `list`/`create` | cross-project | **fixed** |
| `leads.service.ts:69,320` `getById`/`assertOwned` | same-org, not project-scoped | **fixed** |
| `leads.service.ts:155,162` `customer.findUnique`, `conversation.findFirst` | cross-tenant read | **fixed** |
| `leads.service.ts` `getById` returned `null` with HTTP 200 | non-disclosing but inconsistent | **changed to a 404**, matching `deals` |
| `analytics.controller.ts:74-78` `audit-logs` | cross-project read **and authorization in the controller** (§0.4 violation) | **fixed** — query moved to `AnalyticsService#auditLogs` |

### Swept and safe (unchanged)

`views.service.ts:93` (`canAccessProject` first), `accounts.service.ts` (`Account` has
no `projectId`), `notifications.service.ts` (internal/user-scoped),
`cases.service.ts` (project from the conversation), `conversations.service.ts`,
`mock-webhook.controller.ts` (validated by `requireEnabled` → `projectScope`),
`ai/ai.providers.ts`, `agent-status.service.ts`, `escalation`, `knowledge`, `channels`,
`routing`, `employees`, `ai/agents`, `projects`, `customers`, `analytics.service`.

## 10. Proposed guard rules — proposed only, not written

They need an owner and a decision.

**Spring.** A persistence class may not accept a raw project `UUID`; it must accept a
`TenantScope` or `CrmQueryScope`. This closes the two structural gaps found in phase 25
(`ChannelConnectionRepository.findEnabled`, `RollupRepository`) and keeps the safe path
the only path, as it already is for `CrmQueryScope`.

**Legacy.** (a) forbid the `{...ctxDerived, ...body}` ordering, and (b) require
`@RequirePermissions` on every non-`@Public()` handler. Together these catch all three
clinic failures and the `analytics.controller` authorization-in-the-controller case.
Rule (b) is currently unsatisfiable for the clinic module until §6's enum members
exist, so the rules must land in that order or together.

## 11. Files changed

| Path | Change |
|---|---|
| `apps/api/src/clinic/local-clinic.adapter.ts` | every method takes `ctx`; org + project on every query; writes asserted in scope; update fields enumerated |
| `apps/api/src/clinic/clinic-adapter.interface.ts` | `TenantContext` on every method; `CreateClinicAppointmentInput` drops `organizationId` |
| `apps/api/src/clinic/clinic.controller.ts` | `@Tenant()` added, `...body` moved first, `@RequirePermissions` on analytics, gap documented |
| `apps/api/src/clinic/clinic.service.ts` | `projectScope`, `accessibleProjectsScope`, `ctx` threaded |
| `apps/api/src/clinic/scheduling-engine.service.ts` | `ctx` on all four methods |
| `apps/api/src/clinic/reminders.service.ts` | token-only public path, scoped caller path, project-scoped reads |
| `apps/api/src/clinic/public-appointment.controller.ts` | calls the token-only method |
| `apps/api/src/clinic/ai-simulator.service.ts` | `projectScope` on `dto.projectId`; `ctx` threaded |
| `apps/api/src/appointments/appointments.service.ts` | project scope on list/create/get/update/delete; customer lookup scoped |
| `apps/api/src/deals/deals.service.ts` | project scope on list/create/get/update |
| `apps/api/src/leads/leads.service.ts` | project scope throughout; customer/conversation lookups scoped; `getById` 404 |
| `apps/api/src/analytics/analytics.service.ts` | new `auditLogs(ctx, opts)` with `projectScope`/`accessibleProjectsScope` |
| `apps/api/src/analytics/analytics.controller.ts` | delegates; controller no longer builds a `where` |
| `apps/api/src/auth/permissions.guard.ts` | **Javadoc only** — executable lines proven identical |
| `backend/crm-core/src/test/legacy-notes-project-scope/clinic-project-scope.test.ts` | new — 35 tests |

## 12. Commands

```powershell
npx vitest run --config backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts
  # 85 passed, 4 suites
npx vitest run packages/database/src/tenant.test.ts          # 7 passed
npx tsc --noEmit -p apps/api/tsconfig.json                    # exit 0
npx eslint apps/api/src/clinic apps/api/src/appointments apps/api/src/deals apps/api/src/leads apps/api/src/analytics
  # 4 errors, against a 6-error baseline on the same modules — strictly fewer
$env:JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
cmd /c 'gradlew.bat -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true" :backend:crm-core:test --tests "com.firstelement.crm.query.CrmQueryScopeTest" --tests "com.firstelement.crm.CrmCoreIntegrationTest.projectNoteListIsScoped" --console=plain'
  # BUILD SUCCESSFUL — Spring unchanged and still correct against real PostgreSQL
```

The legacy `apps/api/test/*.integration.test.ts` suites were **not** run: they write to
the shared `firstelement` database, which this phase was told never to modify.

## 13. Residual risk

- **`apps/worker` and `packages/**` remain unswept.** This mission covered
  `apps/api/src/**` only. A worker that writes clinic or CRM rows directly would have
  the same class of defect, and nothing in this phase rules that out.
- **The clinic module still requires only a session**, now limited to the caller's own
  tenant. §6's enum members are the remaining work and they need `packages/auth`.
- **Two pre-existing `NotFoundException` messages were normalised** to the
  `{ code, message }` shape the filter expects, in the three clinic paths this change
  already touched. Not a drive-by elsewhere.
- **The harness uses an in-memory Prisma stand-in.** A real-database run of the clinic
  module against seeded cross-tenant data is still owed, and it is the one check that
  would also exercise the audit-gap question in §8.
