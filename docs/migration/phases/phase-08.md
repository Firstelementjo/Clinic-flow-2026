# Phase 8 — Finance ledger and analytics read models

**Status:** IMPLEMENTED — green on `:backend:reporting-finance`, and
`docs/migration/finance-metric-catalogue.md` shipped with a test that fails if the
prose and the enforced catalogue disagree.
**Owner agent:** `firstelement-finance`
**Governing decision:** `docs/migration/adr/0007-finance-and-ai-policy.md`
Part 1. **Do not reinterpret these semantics** — if a requirement appears to
need a different meaning of revenue, stop and escalate.
**Depends on:** Phase 7 (admin/finance role policy and permissions). Requires the
usage-ledger and rate-card migrations to be promoted first (`V20261004000300`,
`V20261004000400`) — request promotion from the database agent; never hand-write
DDL.

## Objective

Build finance from **durable immutable events**, not from dashboard counts. The
acceptance test is simple and non-negotiable: every finance total must drill down
to its source usage events and name the rate-card version that priced them.

## Scope

Build `backend/reporting-finance/**`:

- the metric catalogue and source-event taxonomy;
- immutable `UsageEvent` emission for messaging, AI, storage, exports, seats,
  workflows, time entries, and configurable billable dimensions;
- rate cards, currencies, FX source and rate date, effective dating, markup;
- daily and monthly read models: usage, cost, billable/revenue, margin, unpriced
  usage, forecast;
- period close, approval, lock, correction/adjustment, reconciliation, and
  audited reopen;
- finance role policy and redacted drill-through;
- comparison between the legacy analytics numbers and the new read models.

## Hard rules (from ADR 0007)

1. **Cost is stored; revenue is derived.** Never persist revenue as an input.
2. **No mutable counter becomes money.** If a figure originates from a
   dashboard count, it is not finance truth.
3. **Rate versions are pinned** at computation time; changing a rate card never
   mutates an already-computed snapshot.
4. **Closed periods are immutable** except through an approved, audited
   correction linked to source events. Reopen is permissioned and audited.
5. **Unpriced usage is a first-class view**, visually distinct from zero and
   from "no data". All three states must be distinguishable in the API and UI.
6. **Every amount carries a currency**; FX conversion records source and rate
   date.
7. **No payment execution, no invoices, no external billing changes** in this
   migration.
8. Authorization is applied **before** aggregation, not after.
9. Rate cards are finance-owned configuration; engineering may seed a default
   but must not silently change one.

## Ownership globs

- `backend/reporting-finance/**`
- `settings.gradle.kts`, `backend/app-bootstrap/build.gradle.kts` (wiring only)
- `docs/migration/phases/phase-08.md`, `docs/migration/finance-metric-catalogue.md`

## Acceptance gate

1. Every dashboard amount links to source usage events and the applicable rate
   definition.
2. A closed period cannot change without an approved correction and audit trail
   — proven by a test that attempts it.
3. Unpriced usage is reported distinctly from zero and from no data.
4. Cross-tenant negatives: no membership ⇒ no rows, and an unauthorized
   aggregate reveals neither counts nor sums.
5. Legacy-versus-new analytics comparison documented, with every difference
   explained rather than hand-waved.

## Test commands

```powershell
.\ops\quality\run-gates.ps1 -Stages "backend-compile,backend-unit,backend-integration,security-tenant-negative,export-finance,no-hidden-skips"
```

The `export-finance` stage is currently **red by design** (module absent); it
becomes meaningful when this phase lands and must not be waived away.

## Rollback

Finance is read-only over the ledger, so rollback is disabling the finance route
set. Additive tables only; undo follows `docs/migration/flyway-runbook.md` §9a.
No historical snapshot may be deleted — superseded snapshots are retained per
the retention decision.

---

## What shipped

One module, `:backend:reporting-finance`, 47 main sources and 13 test sources. Every
figure in it resolves to an immutable `usage_events` row and to the rate-card version
that priced it; nothing in the module is a mutable counter promoted to money.

### The chain, and the three places it can break

```
emitter ──► usage_events (append-only)  ──► usage_rollup_snapshots (append-only, generation N+1 supersedes N)
                                              │
                    ┌─────────────────────────┼──────────────────────────┐
                    ▼                         ▼                          ▼
              read models              drill-through              reconciliation
        (usage / unpriced /          (snapshot → rate version   (snapshot vs. an INDEPENDENT
         forecast / margin)           → source events)           ledger sum, two-sided)
```

Each arrow has one rule and one test:

| Arrow | Rule | Suite |
| --- | --- | --- |
| emitter → ledger | idempotent on `(sourceEventType, sourceEventId, dimension)`, inside the emitter's own transaction | `a retried job cannot double-emit` |
| ledger → snapshot | rate resolved **effective on the bucket date** and pinned onto the row | `a rate is effective between its dates, inclusive` |
| snapshot → reader | cost stored, revenue and margin derived; a rate change writes N+1 and never mutates N | `revenue and margin are derived, and pinned` |
| reader → drill-through | the event list windows with the **same expression the snapshot used** | `the drill-through is tenant-scoped and redacted` |
| snapshot ↔ ledger | two independent queries that must agree; disagreement is a named variance, not a gap | `reconciliation compares the snapshot against an independent ledger sum` |

### "Unpriced", "zero" and "no data" are three states, and the API says which

`Money` carries an `AmountState` (`NO_DATA`, `UNPRICED`, `PRICED_ZERO`,
`PRICED_NONZERO`) next to the amount, and `amount()` **throws** rather than returning
`0` for the two absent ones. `UNPRICED` is deliberately not a `UsageDimension`
member: as an enum member it would be something a dashboard could sum next to real
dimensions. A bucket with no contributing events is not materialised at all —
`usage_rollup_snapshots_source_count_check` requires `sourceEventCount >= 1`, and the
read model reports the missing bucket explicitly instead of inventing a zero row.

### Closed periods

`DRAFT → OPEN → IN_REVIEW → LOCKED`, with three terminal refusals that name the
constraint they hit: `relock()` and `returnToReview()` refuse because
`finance_periods_reopened_check` is a biconditional and `REOPENED` is therefore
terminal, and `RateCardService.publish()` refuses because
`firstelement_rate_card_version_guard()` admits only `ACTIVE → RETIRED`. A closed
period moves only through a correction: propose → approve (by somebody else,
enforced by `finance_adjustments_not_self_approved_check`) → apply → recompute, with a
`finance_period_lock_events` row and an `admin_audit_events` row at every step.

### The drill-through

Three levels — `AGGREGATE`, `EVENT`, `PRINCIPAL` — clamped to what the caller's role
permits, with the level actually applied reported on the response so a truncated
drill-through is visible rather than silently less than what was asked for. `metadata`
is never projected; its **keys** are, so the row's shape is auditable without carrying
customer-shaped values. The event list is bounded and carries the bound and the total
count, so a reader knows they are looking at the first N.

### The legacy comparison

`LegacyAnalyticsComparisonService` reads `ai_usage` only, and reports it **beside**
the ledger's own figure. `ai_usage.estimatedCost` is never migrated into the ledger as
an amount (ADR 0009 §3): it is a comparison source, and a disagreement is classified
as a variance with a named cause. Two queries that share code would agree with each
other even when both were wrong, so the reconciliation's snapshot side and ledger side
are separate statements with separate window predicates.

## Defects this phase found and fixed in its own code

1. **The drill-through windowed its event list with a containment test**
   (`periodStart >= bucketStart`), while every rollup used an **overlap**
   (`periodStart <= :to and periodEnd >= :from`). An event's stored bucket is its
   billing bucket — typically the month — so a daily bucket sits *inside* the event's
   bucket rather than containing it. The drill-through therefore returned an **empty
   list for every day of the month except the first**, and `totalSourceEvents` of `0`
   against a snapshot claiming `sourceEventCount > 0`. That is the one failure a reader
   cannot distinguish from "this day had no usage". Found by
   `FinanceTenantIsolationIntegrationTest`. Fixed by splitting the statements per
   grain: `BUCKET_EVENTS_BY_DAY` / `BUCKET_EVENTS` and their count twins, chosen by
   the snapshot's own `grain`, so the drill-through windows with the same expression
   the snapshot was computed from.
2. **The drill-through named an opaque rate-card version id**, so it answered "which
   row" and not "which price". `describeRate` now reads the version's number and its
   effective range tenant-scoped, alongside the snapshot's pinned markup — and falls
   back to the id alone rather than failing if the version row is unreadable.
3. **`RateCardService.publish(...)` was public and unannotated.** It throws, so it is
   unreachable in practice, but it is public on a Spring bean and it is the first thing
   anybody would call. Annotated, and `FinanceRoutePermissionTest` now asserts that
   *every* public method of every finance `@Service` taking a `TenantContext` declares
   a permission.
4. **`RateCardFacade.cards/history` were unannotated delegates.** The service beneath
   them enforced it, but a facade is a reachable bean. Annotated.
5. **`finance_periods."version"` is bumped without an expected version**, so two
   concurrent transitions are last-write-wins. Bounded, audited, and no money is lost —
   the losing transition is a `finance_period_lock_events` row with its own actor and
   timestamp — but it is a real limitation and is recorded in the code rather than
   papered over.

## Schema defects reported to the orchestrator (no DDL written by this phase)

1. **`firstelement_rate_card_version_guard()` makes `DRAFT → ACTIVE` impossible.**
   It admits only `ACTIVE → RETIRED`, so a draft can never be promoted, and the same
   guard refuses `DELETE`, so it cannot be replaced either. `RateCardService.activate()`
   works around it by inserting the ACTIVE version directly with a start day no
   existing version uses, and `publish()` exists solely to explain why. The guard
   should also accept `DRAFT → ACTIVE` with `publishedAt`/`publishedByUserId` set and
   no pricing column moved.
2. **`usage_rollup_snapshots_current_bucket_key`'s partial index predicate is wrong.**
   It filters `supersedesSnapshotId IS NULL`, which selects **generation 1**, not the
   current generation — so the index the query planner is meant to use does not index
   the rows the query reads. Every current-generation read in this module therefore uses
   an explicit anti-join (`not exists (select 1 … where newer."supersedesSnapshotId" = s."id")`)
   instead. The index predicate should be that anti-join, or the index should be
   dropped until a generation predicate is available.
3. **`finance_periods_reopened_check` makes `REOPENED` terminal.** Together with
   `finance_periods_reopen_count_state_check` there is no way back to `IN_REVIEW` or
   `LOCKED`, so a reopened period cannot be closed again and must live as a new period.
   `relock()` and `returnToReview()` refuse with the constraint named. Relax the
   biconditional to the one-way form (`status = 'REOPENED'` implies `reopenCount > 0`,
   not the reverse).
4. **Overlapping ACTIVE rate ranges are prevented only in application code.**
   `rate_card_versions_one_start_per_day` catches same-day collisions; a
   `2026-03-01→2026-03-31` overlapping a `2026-03-15→…` is caught by
   `RateCardService.requireNoOverlap()` after the insert, which rolls back. A
   `btree_gist` `EXCLUDE` over `(rateCardId WITH =, daterange(effectiveFrom, effectiveTo) WITH &&)`
   would make it impossible rather than rolled-back — and `effectiveTo` is nullable, so
   the exclusion needs a coalesce to an open end.

## Policy gap reported to the orchestrator

**There is no finance-specific permission.** Every finance route and service method
declares `ANALYTICS_READ_ORGANIZATION` — the strongest analytics grant there is — and
the distinction between reading a cost and closing a period is made by an `ADMIN` role
check inside the service layer. That is defensible, and `FinanceRoutePermissionTest`
makes it visible, but a reviewer cannot tell a read route from a period-close route by
reading the controller. `FINANCE_READ`, `FINANCE_RATE_CARD_WRITE`,
`FINANCE_PERIOD_CLOSE` and `FINANCE_ADJUSTMENT_APPROVE` owned by
`:backend:tenant-security` would let the four duties be granted separately. Recorded as
a follow-up rather than widened unilaterally.

## Emission hooks needed in other modules (reported, not edited)

Every one of these calls `UsageEventFactory` **inside the emitting module's own
`@Transactional` method** — `UsageEventWriter` is `MANDATORY`, so a call outside a
transaction fails rather than writing a row no business operation could roll back. Each
call is idempotent on `(sourceEventType, sourceEventId, dimension)`, so it is safe from
a retrying worker.

| Hook | Emitting module |
| --- | --- |
| message sent / received, inbound message | `:backend:crm-core` or `:backend:channels` |
| conversation opened / resolved / taken over / returned to AI | `:backend:conversations` |
| channel traffic metered by the provider | `:backend:channels` |
| workflow run started | `:backend:workflows-sla-routing` |
| export job completed, report run produced | `:backend:administration` |
| document chunk indexed / embedded | the Phase 9 AI/RAG module |
| AI run started / completed / failed, tokens recorded | the Phase 9 AI module |
| nightly seat meter (`seatDay`) | scheduler/job runtime |
| nightly storage byte-day meter (`storageByteDay`) | scheduler/job runtime |
| metered API operation (`apiOperation`) | the HTTP filter chain |
| approved time entry (`timeEntry`) | the time module |

## Follow-up debt

- **Rate-card authoring is not on the route set.** `RateCardFacade` exposes reads
  only. Authoring involves a markup, a currency and an effective date, and ADR 0007
  puts it under the administrator capability set; exposing it is a deliberate later
  decision with its own approval workflow, not an omission.
- **`RateCardService.publish()` will need deleting** once defect 1 above is fixed,
  along with the workaround note in its message.
- **No `openapi-paths.json` entries.** All 25 finance routes are new.

## Test commands actually run

```powershell
./gradlew.bat :backend:reporting-finance:test --rerun-tasks --no-build-cache --no-daemon --console=plain
./gradlew.bat :backend:app-bootstrap:compileJava --console=plain
```

**129 tests, 0 failures, 0 skipped.** `:backend:app-bootstrap:compileJava` is green
with the module wired in. Every integration suite runs against the real Flyway chain on
a `pgvector/pgvector:pg16` Testcontainer (pinned to 1.21.4), so a column that does not
exist is a test failure rather than a production surprise.

| Suite | Count |
| --- | --- |
| `FinanceMetricCatalogueTest` | 13 |
| `FinancePeriodLifecycleIntegrationTest` | 28 |
| `FinanceRoutePermissionTest` | 9 |
| `FinanceTenantIsolationIntegrationTest` | 14 |
| `RateCardEffectiveDatingIntegrationTest` | 24 |
| `RollupRateCardMutationTest` | 2 |
| `UsageEventWriterIntegrationTest` | 25 |
| `UsageReadModelStateIntegrationTest` | 14 |

JUnit reports nested `@Nested` classes as separate `testsuite` entries under their
`@DisplayName`, so `expected-suites.json` should carry the **class** names above with
the counts on the right, not the nested display names.

## Hand-off: three contract files this phase cannot edit

### 1. `ops/quality/checks/check-architecture.ps1` — FE-ARCH-001

`$allowedMainDeps` has no `reporting-finance` entry, so the gate reports "module
'reporting-finance' is not in the architecture rule set". It needs its own entry plus
`reporting-finance` added to `app-bootstrap`'s permitted list. Finance reads the ledger
and the audit streams and depends on nothing but the boundary and the shared kernel — it
is not a delivery path, so it must not be permitted to depend on channels, CRM or
notifications:

```powershell
    # Phase 8. Finance prices and reports; it never delivers, so it must not be permitted to depend
    # on channels, crm-core's messaging half, notifications or workflows. It depends on
    # :backend:administration for AdminAuditStreamWriter (a period lock is an administrative action
    # and ADR-0005 §5 gives it a physical stream) and on :backend:crm-core for the jsonb codec.
    'reporting-finance'      = @('shared-kernel', 'tenant-security', 'administration', 'crm-core')
    'app-bootstrap'          = @(..., 'administration', 'operations-observability', 'reporting-finance')
```

### 2. `tests/contracts/openapi-paths.json` — the route inventory

25 routes under `/api/v1/finance` — 14 reads and 11 writes — none in the committed
inventory. Regenerate with the generator, never by hand.

### 3. `tests/contracts/expected-suites.json` — the suite manifest

Eight entries. `FinanceRoutePermissionTest` and `FinanceMetricCatalogueTest` need no
services (no container, no HTTP); the other six need `postgresql` and `pgvector`.

| suite | minTests | kind | services |
| --- | --- | --- | --- |
| `com.firstelement.finance.FinanceMetricCatalogueTest` | 13 | unit | — |
| `com.firstelement.finance.FinancePeriodLifecycleIntegrationTest` | 28 | security | postgresql, pgvector |
| `com.firstelement.finance.FinanceRoutePermissionTest` | 9 | security | — |
| `com.firstelement.finance.FinanceTenantIsolationIntegrationTest` | 14 | security | postgresql, pgvector |
| `com.firstelement.finance.RateCardEffectiveDatingIntegrationTest` | 24 | integration | postgresql, pgvector |
| `com.firstelement.finance.RollupRateCardMutationTest` | 2 | integration | postgresql, pgvector |
| `com.firstelement.finance.UsageEventWriterIntegrationTest` | 25 | integration | postgresql, pgvector |
| `com.firstelement.finance.UsageReadModelStateIntegrationTest` | 14 | integration | postgresql, pgvector |

`minTests` is a floor, not the current count, so these are the reviewed numbers.

### 4. One out-of-globs change in the working tree

`backend/worker-runtime/src/test/java/com/firstelement/worker/WorkerRuntimeHealthIntegrationTest.java`
is modified and is **not** in this phase's ownership globs. It predates this session
(Phase 7 recorded it too) and this phase did not touch it.
### 5. Orchestrator finding: the module did not start, and its own tests could not tell

`app-bootstrap:ApiApplicationSmokeTest` failed with:

```
No qualifying bean of type 'com.firstelement.finance.ledger.UsageWriteAuditor' available
```

**Every `reporting-finance` test passed while the application could not start.** The
difference is who supplies the bean: `FinanceIntegrationTestBase` declares
`usageWriteAuditor()` in a `@TestConfiguration`, so the module's own tests had it,
and the production context had nothing. `UsageWriteAuditor`'s javadoc called `Logging`
"the default, so a deployment gets useful behaviour with no configuration" - a
documented default that no configuration provided.

This is a defect class worth naming, because it is invisible to a module that tests only
itself:

> **A bean supplied by a test harness proves the test harness works, not that the
> application starts.** Any dependency a module needs must resolve in the real
> application context, and `app-bootstrap`'s smoke test is the only thing that checks it.

`app-bootstrap:ApiApplicationSmokeTest` is registered in the required-suite manifest for
exactly this reason, and it earned its place here.

**The fix, and a second finding inside it.** The obvious repair - a `@Bean` method on
`ReportingFinanceModule` - **compiled, shipped in the jar, and still did not register**
(`javap` confirmed the method in `reporting-finance-0.1.0.jar`). The module class is an
`@AutoConfiguration`, and the module convention in this codebase is deliberately
"no `@Bean` here: the services and repositories are annotated and discovered". The bean
now lives in `UsageWriteAuditorDefault`, a plain `@Configuration` class inside the scanned
`com.firstelement.finance` package, so it is discovered by the same component scan that
finds every other finance bean. `ApiApplication`'s own `@Bean` worked throughout, so this
was specific to the `@AutoConfiguration` module class, not to bean processing generally.

Reassurance worth recording: the first repair attempt also used
`@ConditionalOnMissingBean` from `org.springframework.context.annotation`, where it does
not exist - it is in `org.springframework.boot.autoconfigure.condition`. Both the wrong
package and the wrong hosting class were caught by compiling and by the smoke test
respectively, rather than by inspection.

### 6. The test-harness bean, and why both failures were the same mistake

Moving the default into `src/main` immediately broke all eight suites with
`BeanDefinitionOverrideException` - the production bean and the test-config bean were both named
`usageWriteAuditor`. That collision is the second half of the story in section 5, and the two
together are the generalisable part:

1. The test harness declared the bean, so the module passed while the application could not start.
2. Adding the production bean collided with it, so the fix could not land without editing tests.

The lesson is not "be careful with bean names". It is:

> **A test harness must not supply wiring that production supplies.** A bean that exists only in
> test configuration hides its own absence from the module's suites and collides with the real
> default the moment someone adds one. Tests should consume production wiring unchanged, which is
> also the only way they keep telling the truth about whether the application boots.

`FinanceTestConfig` is now empty and exists only as a place to record that. Any future
`@TestConfiguration` bean here needs a reason that production cannot supply.
