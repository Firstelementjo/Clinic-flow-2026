# Phase 38 — `CLINIC_STAFF` exists, and the contract stops lying about login

Branch `migration/spring-angular`. Verdict **PASS**, verified independently rather than taken from
either agent's report.

Gate at close: **1833 backend tests across 269 suites, 0 failures, 0 skipped**; **299 Angular tests,
0 failures**; openapi-contract **162/162**; openapi-document PASS; clean-bootstrap reproduces
`postBoot` exactly on PostgreSQL 16.15; `firstelement` still 45 tables and unstamped.

---

## 1. `CLINIC_STAFF` existed on paper only

Phase 33 gave the clinic 18 role-based permissions across its 21 non-public handlers, and named a
role `CLINIC_STAFF`. **That role was never added to the role enum.** A permission held only by a role
that cannot exist is a comment.

The security engineer found it in **five** places, not the four I predicted. The fifth was the trap:
`packages/database`'s `TenantContext` duplicated the role list as a hand-written two-value union, so
adding the third role broke three call sites until it was typed from `UserRole` instead.

### What it holds, and why

The nine clinic front-desk permissions, and nothing else. **The value is entirely in the denials.**
`EMPLOYEE` carries those same nine *plus* the whole CRM read surface, so before this role the only
way to employ a receptionist was to hand them `NOTE_READ`, `CUSTOMER_READ`, `CONVERSATION_READ`,
`DEAL_READ`, `TASK_READ` — **a booking clerk could read every clinical note in the organization.**
`CLINIC_STAFF` is a strict subset: it can only remove reach, never add it.

Load-bearing proof: removing the enum member and the matrix entry produced **11 test failures and 5
TypeScript compile errors**. The grant is unrepresentable without the role.

### The migration, and the PostgreSQL rule that shapes it

`ALTER TYPE ... ADD VALUE` cannot run inside a transaction block before PostgreSQL 12, and on 12+ the
new value **cannot be used** until that transaction commits. Prisma wraps migrations in a
transaction. The file is therefore **one statement that never uses the value**, which is correct on
11 and later without the caller needing to know the version. Rehearsed on PG 16.15: POS-1 and POS-2
pass, and **NEG-1 fires** `unsafe use of new value`, proving the restriction is live and the
avoidance real. Replay is a no-op via `IF NOT EXISTS`, so a database restored from a backup re-applies
it without aborting.

---

## 2. The half-crossing I had to close myself

The security engineer correctly refused to touch `backend/**` and flagged the consequence:

> `backend/tenant-security/.../UserRole.java` still has two values. Until it is updated, a
> `CLINIC_STAFF` row **cannot be deserialized by Spring** — `fromValue` throws.

`fromValue` **throws** rather than defaulting, which is the right behaviour — an unrecognised role
must not be silently treated as least-privileged — but it means such a user gets a **failed
sign-in**, not a denied action, with no permission decision to audit. That is worse than a refusal and
invisible until somebody assigns the role.

I closed it: the enum member, a real grant in `RolePermissions` (it was resolving to `Set.of()`,
i.e. the role would have done nothing), the Flyway migration `V20261009130000`, and
`UserRoleContractTest` — 5 tests asserting the Prisma enum, the chain and the Java enum agree in
**both** directions, that `fromValue` refuses an unknown label, and that `CLINIC_STAFF` resolves to a
grant and is a strict subset of `EMPLOYEE`.

**Red-to-green:** neutralising the matrix entry turns exactly 2 of the 5 red.

### What this migration cost me, and what it caught

The clean-bootstrap rehearsal failed my **own** migration twice, both with the same unhelpful
message — `type "UserRole" does not exist`:

1. `role_type regtype := '"UserRole"'::regtype` in the `DECLARE`. A declaration default is evaluated
   **before** the block body and cannot be guarded, and under the chain's `search_path = ''` the
   unqualified name resolves to nothing.
2. `ALTER TYPE "UserRole"` one line later — unqualified for the identical reason.

Both are **the Phase 30 defect class, in my own new file**: a guard that depends on a session setting
rather than on database state. My probe had passed them because it ran the setup and the migration in
**two separate psql sessions** and so never reproduced the one-session condition the chain actually
runs under. **A probe that does not reproduce the harness is not a probe.**

Fixed with `to_regtype('public."UserRole"')`, which returns NULL instead of raising so the existence
question is answerable inside the block at all, plus a qualified `ALTER TYPE public."UserRole"`.
Both copies of the chain are byte-identical; fingerprint `fd0bdc74503eb872bcc478c0be43a8c8`.

**Every count is unchanged** — tables 98, enums 57, checks 422, FKs 227 — because `enumCount` counts
enum *types*, not labels. A fingerprint move with a frozen census is the correct signature of this
change, and is exactly why it was worth measuring rather than predicting.

### The rollback refuses, and says why

PostgreSQL has no `ALTER TYPE ... DROP VALUE`. `undo_phase38.sql` refuses when any user carries the
role — dropping it would leave `users.role` holding a string no enum member exists for — and records
that reassigning those users is an operator's decision, not a migration side effect.

---

## 3. Roster writes are audited

`doctor` and `procedure` create/update/delete were permission-gated but wrote **no audit row**. This
is the same gap that made the cross-tenant investigation unanswerable: the writes that would have
shown the breach left no trace.

Six operations, matching the module's existing convention exactly (`auditLog.create`,
`RESOURCE_PAST_TENSE`, no second audit path, no renamed actions). Updates carry a real before/after
diff with a `noOp` marker, so an edit is distinguishable from a no-op. Deletes record the full
pre-image — for the least recoverable action that is the only surviving description.

**Deliberately excluded:** clinician `email`/`phone`. Contact PII would otherwise be copied into
`audit_logs`, which `GET /analytics/audit-log` reads under a wider set of principals than the roster
itself. Recorded as `{field, changed: true}`, never the value.

A cross-tenant actor is refused **and writes no audit row** — both halves asserted separately, with
one test asserting the database was never touched at all, so the refusal provably precedes any write.

**No pre-existing assertion was removed or weakened.** No test anywhere asserted on `auditLog`
contents (verified by grep).

---

## 4. The clinic read model, and what "drill to source rows" cost

The Spring backend had **no clinic module and no analytics module at all**. The clinic journeys had no
counterpart, so they were not comparable and the strangler could not advance past them. Seven
read-only routes now exist.

The requirement that decides whether this is worth merging: **every metric must drill to source
rows.** Each metric group publishes `sourceTable`, window, derivation, per-row `sourceRowIds` and a
`drillThroughPath`, and the fixture **re-derives the population with independent SQL** — so a wrong
derivation fails even when the number happens to be right.

Tenant isolation is tested **negatively**, with a project named in the query and in the body. That is
precisely how the legacy `PermissionsGuard` was defeated, and the lesson is that a scope taken from
the client is a *request*, not authorization.

**Deliberately not built:** revenue, AI conversion rate, utilisation %, satisfaction and daily
trends — each needs a finance definition (ADR 0007) or clinic policy that is not a row. Utilisation
in particular needs a denominator (working hours / slot length / days) that is a business decision.
Refusing to invent them is the correct outcome.

---

## 5. A contract defect I found by verifying rather than trusting

The contract agent reported PASS. Reading the promoted document, I found **`POST /api/v1/auth/login`
declared `200, 400, 415, 429` — and no `401`.**

The route does return 401: `LoginService` throws `invalidCredentials()` for an unknown email, a wrong
password and an inactive account, `AuthenticationFailedException` renders as `401
INVALID_CREDENTIALS`, and two existing tests assert that body. The omission came from the derivation
rule: it derived 401 from `SecurityConfiguration.PUBLIC_PATHS`, and login is public — so **"no session
required" was treated as "cannot answer 401."** A generated client would have been told a rejected
sign-in is a success.

Worse, **two of the contract's own tests encoded the misconception**: one asserted login
`doesNotContain(401)` ("a public mutation is NOT given a 401 — that is the lie this rule exists to
avoid"), and another looped over the public paths asserting none declares 401. I corrected the rule
via the existing `EXTRA_REFUSALS` mechanism, and split the tests: the anti-speculation guard now
covers only `/api/v1/status` and `/api/v1/auth/csrf` (which genuinely cannot 401), and a new test
asserts login **does** declare `401 INVALID_CREDENTIALS`.

The four webhooks correctly declare no 401 — they authenticate by provider signature.

I also closed **`Retry-After`**: the runtime has always sent it (`SecurityExceptionAdvice:159`) and
only the document was silent, so the customiser now declares the header on the login 429, scoped to
operations whose refusal is the login limiter rather than to every 429.

### The characterisation tests that failed because I fixed their defect

My Phase 36 promotion made three frontend tests go red. They were **characterisation tests asserting
a defect was still present** (`expect(raw).not.toContain('Retry-After')`) — they fail *because* the
defect was fixed. The correct response is to **invert them into regression guards**, not delete them:

- **FINDING 2** now asserts a floor of 474 error-response declarations and that login declares 401.
- **FINDING 3** now asserts the `Retry-After` header is declared and typed `integer`.
- **FINDING 5** now asserts JSON is the *majority* media type (576 vs 158) and that the SSE stream
  names `text/event-stream`.
- A fourth asserted the contract declares **no** analytics read model — false now that clinic
  analytics exists. Narrowed to the top-level `/analytics` and `/dashboards` families that
  `PROJECT_REQUIREMENTS` §6 actually requires and that still do not exist.

`frontend: 299 passed, 0 failed`.

---

## 6. Two mistakes of mine this phase

- **A stale test report read as a passing red-to-green check.** I removed the `CLINIC_STAFF` enum
  member to prove the guard fired; that broke compilation, so no fresh XML was written and I read the
  *previous green* one. The correct demonstration neutralised the matrix entry instead — which
  compiles — and I confirmed it from the build output, not a possibly-stale report file.
- **`*/` inside a block comment**, from writing the wildcard media type literally in a doc comment.
  It silently truncated everything after it. The original test had deliberately worded around this;
  I reintroduced it.

---

## 7. Open, and not mine to decide

1. **Revenue / rate card and the utilisation denominator** need ADR 0007 finance semantics and clinic
   policy. Not invented; recorded as product gaps.
2. **`DOCTOR_READ_MANAGEMENT` and `REMINDER_READ`** — Phase 33 put clinician contact behind the
   former, and the permission does not exist in the enum. Both are required before contact details can
   be exposed.
3. **`APPOINTMENT_READ`** is Spring-only with no legacy counterpart, though its grant claims to be
   ported verbatim. Someone should confirm that is intentional.
4. **29 permissions are reachable only through `ROLE_PERMISSIONS[ADMIN] = Object.values(Permission)`**,
   with no explicit grant naming them. `audit-log:read` is the one I would flag hardest: **no
   non-admin role can read the audit trail**, which is exactly the capability that would have answered
   the breach question.
5. **Reads write no audit row anywhere**, so a successful unauthorized *read* remains unanswerable by
   design. §10's "audit the clinic CRUD paths" is now closed for **writes** only.
6. **The forensic scan against production** still needs whoever owns that database to run
   `ops/forensics/Invoke-ForensicScan.ps1`.
7. **Operations decisions, unchanged:** backup encryption mechanism (ADR 0005 §4); `CREATE INDEX
   CONCURRENTLY` vs a maintenance window for `V202610040009`.
