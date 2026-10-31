# Phase 14 — the customer reference namespace, code half (ADR-0014, CX-1)

Status: **code complete, 118/118 green, and the regression suite is proven capable of failing.**

| | |
|---|---|
| Defect | CX-1 — project-scoped `count(*)` allocator + globally unique `cxId` + 5 retries |
| Consequence | at most **five** customer references deployment-wide; the sixth project's first inbound message answered 500, permanently |
| Schema half | `V20261007100000`, `V20261007110000` — landed and verified by Agent B before this change |
| New suite | `com.firstelement.crm.CustomerReferenceNamespaceIntegrationTest` — **15 tests** |
| Module totals | `crm-core` — **118 tests, 10 suites, 0 failures, 0 errors, 0 skipped** |
| Negative control | the pre-fix algorithm was restored and re-run: **5 tests went red, each for the defect's own reason** |

---

## 1. The lesson, and why the shape of the fix matters more than the fix

> **A retry loop over a shared namespace solves contention, not exhaustion.**

The old code was not obviously wrong. It even had a javadoc explaining its reasoning, and the
reasoning was sound at the time: the legacy allocator used a *global* `count(customers)` with no
tenant filter, which was an unscoped read, so scoping the counter fixed a real problem. What the
fix produced was a new one — scoping a counter while keeping the key global converts a uniqueness
constraint into a **scarce, exhaustible resource**. Walk-forward handles contention. It cannot
handle scarcity, and nothing in the design said the namespace was finite.

The number `5` was not a retry count. It was the maximum size of the deployment. A loop that
silently succeeds for the first N and then fails forever is a capacity limit wearing the costume
of a race.

Three things follow, and all three are in the code:

1. **The allocator is asked for a value, never computed from rows.** `count(*)` cannot express
   "what has this tenant *already been issued*", and using it is what made the namespace scarce.
2. **`MAX_IDENTITY_ATTEMPTS` is now documented as a contention bound and nothing else.** The
   javadoc says explicitly that it used to be a capacity number, so the next reader does not
   "fix" a failure by widening it.
3. **The exhaustion error message names the cause it is *not*.** If the loop ever exhausts, the
   message says this is contention or a broken allocator — never scarcity — and tells the reader
   not to widen the constant. A message that only says "failed after 5 attempts" invites exactly
   the widening that hides the bug next time.

---

## 2. What changed

### `CustomerRepository`

| | |
|---|---|
| **deleted** | `nextCxSequence(CrmQueryScope)` — the project-scoped `select count(*) from customers` |
| **deleted** | `cxIdExists(String)` — an unscoped read, and redundant once the composite constraint reports collisions |
| **added** | `nextCxId(CrmQueryScope)` → `select public.firstelement_next_customer_cx_id(:__fe_crm_organization_id)` |

`nextCxId` takes the **`CrmQueryScope`, not a bare `UUID`**, so the organization it allocates for
is `scope.organizationId()` — the authenticated tenant, which the caller can only obtain from
`TenantQueryGuard`. There is no overload through which a request could point the allocator at
another tenant. It throws rather than returning null if the allocator ever returns null.

### `IdentityResolver.resolve`

```java
for (int attempt = 0; attempt < MAX_IDENTITY_ATTEMPTS; attempt++) {
    String cxId = customers.nextCxId(scope);
    ...
    } catch (DuplicateKeyException e) {
        log.debug("cxId {} was taken concurrently; asking the allocator again", cxId);
        continue;
    }
```

The `+ attempt` arithmetic and the `cxIdExists` pre-check are both gone. `attachIdentity`'s
existing duplicate-handling is untouched: a concurrent delivery that wins the handle still
returns its customer rather than orphaning one, and `knownHandleIsIdempotent` /
`verifiedSignalStillLinksAndConsumesNoReference` in the new suite plus the four existing
identity tests in `CrmCoreIntegrationTest` all still pass.

Note that the retry loop now **asks the allocator again** rather than stepping a candidate. That
consumes a reference per retry. That is correct and worth saying plainly: a *gap* in the sequence
is harmless, a *reuse* is not, so the cheaper-on-values option would be the wrong one to optimise.

### `TicketNumber.generateCxId` — fenced, not deleted

The trap the schema agent flagged, defused where the next reader will hit it rather than only in
the call site:

> **Not for allocating a customer reference.** The allocator is
> `public.firstelement_next_customer_cx_id(uuid)` in the database; it returns the finished
> `#33XXXXXX` string and it is the only supported way to obtain one. Passing its output through this
> method double-formats it: `generateCxId(33000002)` is `#3333000002`, thirteen characters, and
> `customers."cxId"` is `varchar(9)`.

The method stays because it is still the right tool for a **seed or migration** that has to
reproduce the legacy numbering deterministically — the TypeScript packages call their own copy for
exactly that, and `TicketNumberTest` covers it. Deleting it would break legacy parity for no gain.

---

## 3. A fixture gap I had to close, and why it is the same bug

`CrmFixture.reset()` inserted customers `#33000001`, `#33000002`, `#33000003` directly and left
`customer_cx_sequences` empty. With the new allocator, a fresh counter hands out `#33000000` and
then **`#33000001` — which the seeded `customerA1` already holds**. Every allocation in every test
would have spent two attempts walking onto a live reference, and the contention loop would have
been doing work the production data never asks it to do. The tests would have passed for the wrong
reason, and `concurrentWritersReceiveDifferentReferences`'s counter assertion would have been
measuring retries instead of allocations.

That is not a hypothetical gap — it is the same defect one layer down. A `count(*)`-derived
allocator cannot know what a tenant has already been *issued*; the counter was introduced with a
backfill migration precisely for that reason, and the fixture was seeding the rows without the
backfill. It is exactly the shape of mistake that let CX-1 ship.

`CrmFixture.reset()` now calls `backfillCxSequences()`, which is `V20261007110000`'s statement
verbatim — `max((substr("cxId", 4)::bigint))` grouped by organization, `on conflict do nothing` —
derived from the rows rather than hard-coded, so it stays correct if the seeded references change.
Alpha's counter is `2` and Beta's is `3`, and several tests assert those exact values.

---

## 4. The regression suite, and the proof it can fail

`CustomerReferenceNamespaceIntegrationTest` — 15 tests, against the real Flyway chain in a real
pgvector container.

| # | Test | What it pins |
|---|---|---|
| 1 | **six PROJECTS can each take a first reference** | the capacity regression. Pre-fix this **threw on the sixth** |
| 2 | **six ORGANIZATIONS each take a first reference, and all six are the SAME one** | the per-tenant privacy property. A global sequence would make them differ, and then a tenant could read the deployment's total customer count off its own numbering |
| 3 | successive allocations in one organization strictly increase | monotonicity, and that **one allocation happens per creation** (counter moved by exactly 5) |
| 4 | **a reference is never reused after its holder is deleted** | the second ADR-0014 fault, independently of exhaustion |
| 5 | deleting a TENANT removes its counter; deleting a customer never does | the cascade direction, so it cannot be quietly reversed |
| 6 | **concurrent writers receive DIFFERENT references** | 8 threads, one org, a start gate; asserts 8 distinct values *and* that the counter moved by exactly 8 |
| 7 | a handle in another tenant resolves to a SEPARATE customer here | tenant isolation on the handle, asserting the id is not the other tenant's |
| 8 | a project the caller cannot reach is refused, and **burns no reference** | a denial must not consume the namespace |
| 9 | a claimed tenant that is not the caller's is refused, and burns none | a request is never authorization |
| 10 | a principal with no membership receives nothing and allocates nothing | FR-1.4 |
| 11 | the allocator refuses an organization that does not exist | the FK *is* the DB-side tenant boundary for the function |
| 12 | a created customer carries `^#33\d{6}$`, 9 chars, sequence in 0..999999 | the customer-visible format |
| 13 | a verified signal still links and consumes no reference | existing behaviour that must not regress |
| 14 | a known handle resolves twice to one customer and allocates nothing | existing idempotence |
| 15 | a name-only overlap is reported, never merged, and still allocates | existing behaviour, and the reference is allocated for the new customer |

### The negative control — the part that makes the rest mean something

A regression test that passes against broken code is decoration. So I restored the pre-fix
algorithm verbatim (backing both files up first and restoring them byte-identically afterwards) and
re-ran the suite:

| Test | Result against the pre-fix code |
|---|---|
| six PROJECTS | **RED** — `IllegalStateException: Could not allocate a customer reference after 5 attempts` |
| six ORGANIZATIONS | **RED** — same exhaustion |
| successive allocations strictly increase | **RED** — `expected: 7L but was: 2L` (a `count(*)` never moves the counter) |
| no reuse after deletion | **RED** — `#33000004` handed out **again** |
| concurrent writers | **RED** — `current transaction is aborted` (the count read inside an aborted transaction) |

The other ten stayed green, which is correct: they are guards that the defect did not change.

The first line is the proof ADR-0014 asked for. *"This test would have caught it at the moment it
was written."* It does.

---

## 5. Tests run and exact commands

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'
$env:GRADLE_OPTS='-Djava.net.preferIPv4Stack=true'
.\gradlew.bat :backend:crm-core:test --rerun-tasks --no-build-cache --no-daemon --console=plain `
  '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true'
# BUILD SUCCESSFUL -- 118 tests, 10 suites, 0 failures, 0 errors, 0 skipped
```

**On the environment note — it is fixable, and here is the fix.** Gradle's daemon starts, binds to
`127.0.0.1`, and the client then fails to reach it:

```
Daemon server started.
* What went wrong: Could not connect to the Gradle daemon.
```

The `hosts` file has both `# 127.0.0.1 localhost` and `# ::1 localhost` **commented out**
("resolution is handled within DNS itself"), so `localhost` resolves to `::1` first while the daemon
listens only on IPv4. Forcing IPv4 fixes it — and it must be forced on **both** sides:

* `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true` reaches every JVM Gradle forks, **including
  the test workers** (without it the daemon connects and `Gradle Test Executor` fails with
  `BindException: Cannot assign requested address`);
* `org.gradle.jvmargs` needs `-Djava.net.preferIPv4Stack=true` too, passed on the command line so
  no file outside my ownership has to change.

This is worth recording because a previous agent on this host reported the same symptom as
unfixable. It is not; it just needs the flag on the worker JVMs, not only on the daemon.

---

## 6. Suite entries for `tests/contracts/expected-suites.json`

I do not own that file. One new entry is needed:

```json
{"suite":"com.firstelement.crm.CustomerReferenceNamespaceIntegrationTest","module":"crm-core","stage":"backend-integration","minTests":15,"kind":"integration","services":["postgresql","pgvector"],"note":"Phase 14 (ADR-0014, CX-1): the capacity regression -- six projects AND six organizations each take a first customer reference; strict monotonicity; no reuse after deletion; the cascade direction (only deleting the TENANT removes the counter); concurrent writers receive different references; cross-tenant handle, cross-project scope, foreign caller and claimed-tenant negatives"}
```

**No existing `minTests` needs to move.** `CrmCoreIntegrationTest` stays at 33 — the change did not
add or remove a test there. For the record, the measured counts in this module are:

| Suite | Tests |
|---|---|
| `com.firstelement.crm.CustomerReferenceNamespaceIntegrationTest` **(new)** | **15** |
| `com.firstelement.crm.CrmCoreIntegrationTest` | 33 |
| `com.firstelement.crm.timeline.TimelineEventMapperTest` | 13 |
| `com.firstelement.crm.query.CrmQueryScopeTest` | 11 |
| `com.firstelement.crm.query.InboxFilterDefinitionTest` | 10 |
| `com.firstelement.crm.domain.TicketNumberTest` | 8 |
| `com.firstelement.crm.error.CrmDomainExceptionTest` | 8 |
| `com.firstelement.crm.api.TimestampsTest` | 7 |
| `com.firstelement.crm.query.CursorPageTest` | 7 |
| `com.firstelement.crm.casework.CaseSolutionMatchingTest` | 6 |

> **Pre-existing drift, reported and not touched.** `CursorPageTest` is recorded at `minTests: 6` and
> measures **7**. `minTests` is documented as a floor a human reviewed, so the extra test is fine —
> but the manifest and reality disagree, and a suite that gains tests without a deliberate entry is
> exactly what the no-hidden-skips gate is meant to surface. It is not mine to change; flagging it
> for the owner.

---

## 7. Security / tenant checks

* **The allocator cannot be pointed at another tenant by a request.** `nextCxId` reads
  `scope.organizationId()`, reachable only through `TenantQueryGuard`. There is no `UUID`-taking
  overload.
* **The unscoped read is gone.** `cxIdExists` took no scope, could not be given one, and disclosed
  cross-tenant existence through both its result and its latency (ADR-0014 defect list item 3,
  F-14-2). It no longer exists.
* **Cross-tenant handle** resolves to a *separate* customer in the calling tenant, and the test
  asserts the returned id is **not** the other tenant's customer rather than only counting rows.
* **Cross-project scope**, **claimed-tenant mismatch** and **no-membership** all refuse, and each
  asserts the counter did **not** move — a denial must not consume the namespace, which is a small
  way a tenant could otherwise be pushed toward its ceiling by another tenant's traffic.
* **The counter row's tenant guarantee is the FK**, and it is asserted rather than assumed:
  allocating for an organization that does not exist raises `DataIntegrityViolationException` and
  leaves no counter row behind.
* No secret, token or credential anywhere in the change. `log.debug` logs the reference, which is
  a customer-visible identifier an operator already sees in the UI — not a credential.

---

## 8. Migration / data impact

**None.** No migration, no DDL, no DML outside the test fixture. The schema half was already
landed and I changed none of it. The dev `firstelement` database is verified untouched: 45 tables,
0 `flyway_schema_history` rows, 4 users, 3 customers with their original `#33000000…2`,
`customer_cx_sequences` absent and `customers_cxId_key` still present. The suite runs against
Testcontainers (`pgvector/pgvector:pg16`), not the dev database.

---

## 9. Known risks

1. **A retry now consumes a reference.** Each contention retry asks the allocator again, so the
   sequence advances. Correct — a gap is harmless and a reuse is not — but it means the counter is
   not a count of customers, and any future code that tries to reconcile the two will be wrong.
2. **The retry loop is now nearly dead code**, which is a mild smell: unreachable-ish defensive
   code can rot. It is kept because the atomic `ON CONFLICT` protects the *allocator*, not the
   insert — a value can still in principle be taken between allocation and insert by a future
   writer — and because removing the only defence against a lost write would be worse. It is
   documented as contention, not capacity, so nobody "simplifies" it away by widening it.
3. **`CrmFixture` now depends on `customer_cx_sequences` existing.** If the schema half were undone
   (`ops/flyway/rollback/undo_phase14.sql`), every `crm-core` integration test would fail at
   `reset()`. That is correct behaviour — the fixture models the post-migration schema — but the
   undo now breaks the build rather than just reverting a version, which is worth knowing before
   anyone runs it on a shared environment.
4. **`TicketNumber` and the database formatter can drift.** The Java formatter and the SQL
   `'#33' || lpad(seq, 6, '0')` are the same rule in two places. They are fenced by comments on
   both sides, and `TicketNumberTest` covers the Java one; a single-source version would need the
   rule in the database, which is where the authority now lives.
5. **One test is inherently timing-shaped**: the concurrency test uses a latch and 8 threads on a
   shared container. If it ever flakes it will almost certainly be resource starvation rather than
   a lost reference — the allocator cannot hand out a duplicate — so a flaky run should be read as
   "the harness ran out of threads", not "allocation raced". The counter assertion would catch a
   genuine lost allocation.
6. **F-14-1 (schema half, unchanged by me)**: the high-water mark is reconstructible only from
   *surviving* `customers` rows. A reference held by a customer deleted *before* the migration
   cannot be recovered, and no code change can fix that — only a ledger can.

---

## 10. Follow-ups

| | |
|---|---|
| **P0 → done** | the capacity regression is closed and proven by a test that fails without the fix |
| **1** | Add the suite entry in §6 to `tests/contracts/expected-suites.json` (orchestrator owns the file) |
| **2** | Consider the `CursorPageTest` `minTests: 6` → 7 drift in the same edit (owner decision, not a code fix) |
| **3** | `packages/messaging/src/identity-resolver.ts` and `packages/shared/src/ticketing.ts` still carry the legacy `count(*)` + `generateCxId` path. They are out of my globs and out of the strangler scope until their modules are retired, but they are a live copy of the defect for as long as they exist. |
| **4** | Re-gate: the Phase-11 Batch A report is BLOCKED on this and can now be re-evaluated |
| **5** | `./gradlew build` remains the orchestrator's gate; I ran `:backend:crm-core:test` only |