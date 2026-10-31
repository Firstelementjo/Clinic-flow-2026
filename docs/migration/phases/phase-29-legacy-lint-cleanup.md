# Phase 29 — legacy lint cleanup: typing the evidence, not silencing it

Status: **gate green.** Warnings 53 → 33 (ceiling 39). Errors unchanged at 116
(ceiling 118). No pin raised, no rule disabled, no config touched.

| | |
|---|---|
| Trigger | `legacy-node-static` red: warnings 53 against a ceiling of 39 |
| Cause | 20 warnings, all `@typescript-eslint/no-explicit-any`, introduced by phases 24–28 |
| Fixed | `packages/ai/src/tools/clinic-scheduling.test.ts` (19), `validation-pipe-reachability.test.ts` (1) |
| Left alone | 33 pre-existing warnings, per instruction — see §3 |
| `tests/contracts/lint-baseline.json` | **untouched** |
| `eslint.config.mjs` | **untouched by this phase** (the `frontend/**` scope correction is the orchestrator's) |
| Behaviour change | none — §5 records the one double that became stricter, and why |
| Assertion count | 75 → 76: **none removed, none weakened, one added** |

---

## 1. Why this was worth doing rather than doing quickly

`clinic-scheduling.test.ts` is the evidence for a tenant-isolation fix on a
prompt-injection surface. `any` is not cosmetic there: `toHaveLength(1)` on an
`any[]` is satisfied by a value of entirely the wrong shape, and a cast through
`as any` can hide the very mismatch the assertion exists to catch. Typing the
doubles is therefore part of the proof, and the report has to be able to show that
the assertions did not move.

## 2. What changed

### `packages/ai/src/tools/clinic-scheduling.test.ts` — 19 → 0

Row types were declared once, matching the clinic tables the doubles stand in for:

```ts
interface TenantRow { id: string; organizationId: string; projectId: string }
interface DoctorRow extends TenantRow { name; specialty; workingDays; … }
interface ProcedureRow extends TenantRow { … }
interface CustomerRow extends TenantRow { displayName; primaryPhone; primaryEmail? }
interface AppointmentRow extends TenantRow { appointmentDate: Date; doctor?; procedure?; … }
interface AuditLogRow { … }
/** The `where` fragment the doubles match on. */
interface ScopeWhere { id?; organizationId?; projectId?; doctorId?; isActive? }
```

The structural change is `makeFakePrisma`, which used to erase itself to
`PrismaClient` and then be cast *back* through `as any` to reach the recorded rows:

```ts
// before: the escape hatch every cross-tenant test reached through
const prisma = makeFakePrisma();
expect((prisma as any).__appointments).toHaveLength(before);
expect((prisma as any).__auditLogs).toHaveLength(0);
expect(prisma.appointment.create).not.toHaveBeenCalled();

// after: a typed handle, and the load-bearing assertion reads the rows directly
const fake = makeFakePrisma();
expect(fake.appointments).toHaveLength(before);
expect(fake.auditLogs).toHaveLength(0);
expect(fake.mocks.appointmentCreate).not.toHaveBeenCalled();
```

That is the substantive part of the change. It removes nine `as any` casts
**and** makes the "nothing was written" assertions read the recorded rows rather
than a cast projection of them.

The remaining sites were typed individually:

| Site | Typed as | Why that is the narrowest honest type |
|---|---|---|
| mock implementation args | `{ where: ScopeWhere }`, `{ data: AppointmentRow }`, `{ where: { id: string }; data: Partial<AppointmentRow> }` | the shape the double was declared to receive — also removes six implicit `any`s |
| `outcome.result.doctors` | `DoctorResult[]` | the exported mapped shape the tool actually returns, not the Prisma row |
| mock-call read | `Array<Array<{ where: ScopeWhere }>>` | `mock.calls` is an array of *argument tuples*, so `calls[i][j]` is the j-th argument; the double takes one |
| the pre-fix predicate reproduction | `Record<string, unknown>` | it really did build a dictionary, and typing it that way is what makes the missing tenant column visible instead of hidden behind a cast |

### `validation-pipe-reachability.test.ts` — 1 → 0

The warning was `@Body() body: any` in the probe that mirrors
`clinic.controller.ts`. The fix is `Record<string, unknown>`, and this is **not**
"an `any` that happens to typecheck":

- The **runtime** type is identical and is the whole thing under test. TypeScript
  emits `Object` as the design-time metatype for `any`, for an inline object
  literal, and for an index-signature type, because none names a class.
  `ValidationPipe.toValidate()` skips a metatype of `Object`, so all three reach the
  handler untouched.
- The sibling `PlainBodyController` in the same file, typed
  `@Body() body: { title: string }`, already emits `Object` and already passes —
  so the `Object` path is proven in this harness, not assumed.
- `Record<string, unknown>` is the honest description of the value: an object of
  unknown-valued keys, because the request body was never validated. `any` claimed
  less, not more.

The probe's test still passes, which is the empirical confirmation that the
metatype did not change.

## 3. Pre-existing warnings: measured, not assumed

The instruction was to clear `ai-simulator.service.ts` and `clinic.controller.ts`
**only** if the warnings were introduced by phases 24–28, and to leave
pre-existing ones so the true new-warning count is visible. I did not take that on
trust — I linted the pre-phase tree.

`git checkout 0d45d02 -- <the three clinic files>` (the commit before the security
work), then `npx eslint` on exactly those paths:

| File | Warnings **before** phase 24 | Warnings **now** | Introduced by phases 24–28? |
|---|---|---|---|
| `apps/api/src/clinic/ai-simulator.service.ts` | 6 (L30, 31, 49, 297, 331, 418) | 6 (L30, 31, 49, 303, 338, 425) | **no** — identical rules, lines shifted only by the `projectScope` edit |
| `apps/api/src/clinic/clinic.controller.ts` | 5 (L50, 62, 92, 104, 169) | 5 (L76, 91, 122, 135, 202) | **no** — identical, shifted by the added `@Tenant()` and the class Javadoc |
| `apps/api/src/clinic/local-clinic.adapter.ts` | 2 (L51, 186) | 2 (L124, 297) | **no** — identical, shifted by the added `ctx` parameters |

**All 13 are pre-existing, and I left every one of them.** The files are unmodified
by this phase (`git diff -- apps/api/src/clinic` is empty).

The other 20 remaining warnings are in files phases 24–28 never touched:
`packages/database/src/seed-test-conversations.ts` (5), `apps/web/src/lib/{clinic-ai,customers}.ts`
(3 each), `apps/web/src/routes/LoginPage.tsx` (3),
`Design dental clinic login page/vite.config.ts` (2), and four one-off files under
`apps/web/src/{components,routes}` (1 each).

So the true new-warning count from phases 24–28 was **20, and it is now 0.**

## 4. Gate totals

| | Before | After | Ceiling |
|---|---|---|---|
| errors | 116 | **116** | 118 |
| warnings | 53 | **33** | 39 |

Errors did not move, which is the point: nothing was traded. The 20 removed
warnings were all `no-explicit-any` in two files, and every other warning that
existed before this phase still exists — including the 13 in the clinic module that
a cosmetically-minded pass would have taken the opportunity to clean up.

## 5. The assertions did not move

Compared the `expect(` lines in my phase-28 version (recovered from the backup taken
at the start of this phase — `git show HEAD:` would have compared against the
**pre-phase-28** file, since that work was uncommitted) against the current file:

- **75 assertions before, 76 after.**
- Every removed line has an exact counterpart: `as any[]` → `as DoctorResult[]`,
  `(prisma as any).__appointments` → `fake.appointments`,
  `prisma.appointment.create` → `fake.mocks.appointmentCreate`,
  `where.organizationId` → `calls[0]?.[0]?.where.organizationId`, and so on.
  Same matcher, same expected value.
- **One assertion added:** `expect(calls).toHaveLength(1)`. Nothing previously
  asserted that the roster query ran exactly once, so this is a strengthening.

Both suites are green: 34/34 in the AI clinic tools (240/240 across `packages/`)
and 85/85 in the phase 24–26 security harnesses.

### One double got stricter, and it is worth naming

The `appointment.update` double used to fall through to `return { id, ...data }`
when the row was not found — it **invented** a row. It now throws, which is what
Prisma does. No test reaches that branch (reschedule and cancel only update after a
scoped `findFirst` has already found the row, and the cross-tenant tests assert
`update` is never called), so no assertion changed and all 34 still pass.

The old behaviour was the wrong one for this file specifically: a double that
invents a row on a miss would let a write to a row that was never in scope appear
to succeed. That is the exact failure this suite exists to catch, and it was one
line away from hiding it.

## 6. Files changed

| Path | Change |
|---|---|
| `packages/ai/src/tools/clinic-scheduling.test.ts` | types only (+ the `FakePrisma` handle) |
| `backend/crm-core/src/test/legacy-notes-project-scope/validation-pipe-reachability.test.ts` | one parameter type + explanation |
| `docs/migration/phases/phase-29-legacy-lint-cleanup.md` | this report |

## 7. Commands

```powershell
npx eslint . -f json                    # errors 116, warnings 33
npx tsc -b tsconfig.build.json          # exit 0, all workspaces
npx tsc --noEmit -p apps/api/tsconfig.json   # exit 0
npx vitest run packages/                # 240 passed, 18 files
npx vitest run --config backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts
  # 85 passed
```

## 8. Residual risk

- **33 warnings remain, all pre-existing and all `no-explicit-any`** — 13 in
  `apps/api/src/clinic/**`, 20 in `apps/web`, `packages/database` and a stray
  `Design dental clinic login page/` directory. They are under the ceiling today
  but the ceiling is a ratchet, not a budget to spend.
- **`apps/web` is the largest single cluster and has no type discipline in this
  config at all.** It is a React tree being replaced by Angular; the warnings are
  cheap to fix and will become irrelevant at cutover, so the call on whether to
  spend time there is a product decision, not a lint decision.
- **The `any` body probe in `clinic.controller.ts` itself is still `any` in
  production code** (pre-existing, and I left it). Phase 26 documented that
  `@RequirePermissions` cannot be added to that module until the permission enum
  gains clinic members; typing its bodies is a separate, small piece of that work.
- **One behavioural improvement to a test double is in this diff** (§5). It is
  strictly stricter, and it is the only non-typing change.
