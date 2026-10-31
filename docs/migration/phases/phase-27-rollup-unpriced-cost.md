# Phase 27 — FIN-01: a priced rollup bucket recorded its cost as unpriced

**Status:** PASS
**Scope:** `backend/reporting-finance/**` only. No schema change, no other module touched.
**Defect class:** writer-side argument transposition + a reader-side unit conflation, in the same
`unpricedCost` figure.

---

## 1. Which side was wrong, and how that was decided

**The call site was wrong, not the factory and not the schema.** Four independent lines of
evidence, none of which is "the test said so":

1. **`usage_rollup_snapshots_derivation_check`.** The schema requires
   `revenueAmount = round(costAmount × markupRate, 6)` — applied to `costAmount` **in full**. On a
   priced row every unit of the bucket's cost has been marked up. There is therefore no subset of
   `costAmount` that could be unpriced, and a priced row whose `unpricedCostAmount` equalled its
   `costAmount` is arithmetically self-contradictory.
2. **`usage_rollup_snapshots_unpriced_pair_check`.** It constrains only rows where
   `rateCardVersionId IS NULL` — an unpriced row must carry positive unpriced quantity *or* cost.
   It imposes nothing on a priced row, so nothing in the schema asked for the value that was being
   written. It also cannot catch it: shadow parity recorded exactly this.
3. **`RollupBucket.hasUnpricedResidue()`'s own documentation** (`RollupBucket.java` §"A real state
   that the three-state model alone does not cover"): *"a rate card that prices a dimension with a
   markup but no per-unit cost will mark up the costs that were recorded and leave the events that
   had none unpriced."* The residue is the `NULL`-cost events' **usage**. Its cost is the sum over
   events that have no cost.
4. **`V13_usage_finance_slice.sql` §9a**, the schema's own first-class unpriced view, sums
   `unpricedQuantity` and `unpricedCostAmount` **only over `rateCardVersionId IS NULL` rows** — the
   authoritative statement of which rows carry unbilled money.

The tell that the call site was written by copying the other factory: `RollupBucket.unpriced(...)`
takes no unpriced arguments and derives `(quantity, costAmount)` internally, and the defective call
passed `(ledger.uncostedQuantity(), cost)` — the unpriced factory's *pair*, in the unpriced factory's
*order*, into the priced factory. Same shape, wrong column.

---

## 2. The fix

### Writer

`RollupRepository` — both ledger aggregate statements gained the cost half of the residue:

```sql
coalesce(sum(u."costAmount") filter (where u."costAmount" is null), 0) as "uncostedCostAmount"
```

This is zero **by construction** (it sums no rows) and is still written as a sum rather than a Java
literal `0`. The two halves of the residue are then derived from one predicate, so they cannot
disagree about which events are uncosted — and the value stays correct if the predicate is ever
widened.

`RollupService.computeInScope` passes the residue:

```java
new RollupBucket.UnpricedResidue(ledger.uncostedQuantity(), ledger.uncostedCostAmount())
```

### Structural, not just corrected

`RollupBucket.priced(...)` no longer takes two adjacent `BigDecimal`s. It takes a named
`UnpricedResidue(BigDecimal quantity, BigDecimal cost)`. Two same-typed arguments in a row are
transposable by a caller and a transposition there is not a compile error — it is a finance figure.
The defect class is now unrepresentable at the call site rather than merely tested for.

### Reader — a second, independent defect found while checking the readers

`FinanceUsageReadService.assemble` accumulated `unpricedQuantity` and then passed **that quantity** to
`Money.of(...)` as the `Unpriced.cost` field. So `FinanceUsageView.Unpriced.cost()` returned a
*count of units* wearing a currency, and `FinanceController.closeReadiness` publishes it verbatim as
`"unpricedCost"` on `GET /api/v1/finance/periods/{id}/close-readiness`. A window with 40 unpriced
chunks reported `40.000000 USD` unbilled.

Fixed to sum `unpricedCostAmount` for the money and keep `unpricedQuantity` for the quantity.
This is a **second, separate bug** — it would have survived the writer fix, which is exactly why the
readers are asserted independently.

---

## 3. Every reader of `unpricedCost` / `unpricedQuantity` that was checked

| Reader | Reads | Verdict |
| --- | --- | --- |
| `RollupRepository.insert` (write) | `bucket.unpricedCost()` | **Writer.** Correct now; coerces null → `0` on insert, which matches the `NOT NULL DEFAULT 0` columns. |
| `RollupRepository.CURRENT_BUCKETS` / `CURRENT_BUCKET` / `HISTORY` | both columns, raw | Correct pass-through. No arithmetic. |
| `DrillThroughService.resolve` (own statement) | both columns, raw | Correct pass-through. No arithmetic. |
| `FinanceUsageReadService.assemble` | both columns | **Was wrong** — handed the *quantity* to `Money.of`. Fixed. |
| `FinanceController.closeReadiness` | `window.unpriced().cost()` | Pass-through; inherits the fix. The payload key `unpricedCost` is now an amount, which is what the key says. |
| `ReconciliationService` | **neither column** | Correct. `snapshotTotals`/`ledgerTotals` sum `costAmount` only. Asserted anyway, because "the reconciler does not read it" is a claim about SQL, and a future reader adding the column would silently double. |
| `ForecastService` | **neither column** — `unpricedTotal` sums an `Accumulator.cost` built from `costAmount` | Correct. `FinanceForecast.hasUnpriced()` deliberately keys off dimensions, not the amount. |
| `ReportField.USAGE_UNPRICED_QUANTITY` (`backend/reporting-exports`, not owned) | `sum(s."unpricedQuantity")` | Correct and unaffected: the *quantity* half's meaning is unchanged (uncosted usage on a priced row; whole bucket on an unpriced row). It does not read `unpricedCostAmount`. Exports suite re-run green. |
| `FinanceExportGateRepository` (not owned) | **neither column** | Correct — it is a gate, reads only period status and snapshot coverage. |
| `V13_usage_finance_slice.sql` §9a (`ops/flyway`, not owned) | both, filtered `rateCardVersionId IS NULL` | Correct, and now agrees with the stored data. |

### Did the DAILY grain or another call site share the fault?

**Yes — both grains, and there is exactly one call site.** `computeDaily` delegates to
`computeRange`, which delegates to `computeInScope`, so the MONTHLY path reached the identical
expression. There is no second construction site: `RollupBucket.priced` / `.unpriced` are
package-private and grep finds one use of each. Both grains are now asserted separately
(`theMonthlyGrainIsFixedToo`) rather than inferred from the shared method — "they share one method"
is a claim about the code, not about a stored figure.

---

## 4. Tests — and the proof they are load-bearing

`RollupUnpricedCostIntegrityIntegrationTest` — 13 tests, 4 nested classes.

- **Writer, priced:** unpriced cost ≠ cost (relationship, asserted before the literal); stored row
  agrees with the returned bucket; MONTHLY grain; priced-with-residue (positive
  `unpricedQuantity`, zero `unpricedCost`).
- **Writer, unpriced:** no-rate bucket records cost as unpriced; a rate that does not span the
  month is recorded unpriced, not half-priced. **This is the assertion that forbids "fixing" the
  bug by zeroing both columns everywhere** — and
  `usage_rollup_snapshots_unpriced_pair_check` would refuse such a row anyway, so the bug would move
  rather than disappear.
- **Four states:** priced / priced-zero / unpriced / no-data each keep their own unpriced
  signature; the gap is enumerated, not materialised.
- **Readers:** read model; a tenant-scoped SQL sum of `unpricedCostAmount` **with no state filter**
  (V13's shape); the reconciler; the drill-through's own snapshot statement. A tenant-isolation pair
  with a positive control.

### Red-to-green evidence

| Reverted | Tests run | Failed | Pre-existing tests affected |
| --- | --- | --- | --- |
| `RollupService`: `uncostedCostAmount()` → `cost` | 142 | **8** | **0 of 134** |
| `FinanceUsageReadService`: `unpricedCost` → `unpricedQuantity` in `Money.of` | 142 | **3** | **0 of 134** |
| Both restored | 142 | 0 | — |

The writer revert fails the four `PricedWriter` tests, `FourStates`, the SQL-sum test, the
drill-through test and the tenant aggregate. The reader revert fails only the three tests that assert
a *money* figure — and leaves every writer test green, which is what shows the two fixes are
independently load-bearing and neither masks the other.

**The load-bearing finding: with either defect reintroduced, all 134 pre-existing finance tests stay
green.** The defect survived the 1599-test backend gate. Nothing in the suite read the relationship
between a bucket's cost and its unpriced cost, and no test opened a window that contained both a
priced and an unpriced dimension and then summed the unpriced columns.

---

## 5. Tenant isolation

Unchanged and re-asserted. Every statement keeps its `:__fe_tenant_organization_id` predicate from
`ScopedWhere`; the new test's SQL sums are tenant-scoped and one negative asserts
`PolicyDeniedException` for a principal in the right tenant but the wrong project, **with a positive
control** on a project the principal does hold — without it, a reader returning `NO_DATA` for
everything would satisfy the refusal.

Tenant B is seeded with 900.000000 priced and 800.000000 unpriced so a missing predicate yields a
visibly wrong exposure rather than a plausible one.

---

## 6. Migration / data impact

**None.** No DDL, no DML, no `ops/flyway/**` change, no change to the `firstelement` database.

The **writer** change only affects snapshots computed *after* deployment. Snapshots already written
carry the defective `unpricedCostAmount` and are **not** corrected: `usage_rollup_snapshots` is
append-only, a recomputation is a new generation, and correcting history silently is exactly what
ADR 0007 rule 4 forbids. If the orchestrator wants historical rows corrected, that is a decision to
make explicitly, per period, through reopen + recompute or an approved `finance_adjustments` row —
and only for periods that are still open.

The **reader** change needs no backfill at all: it stops summing the wrong column, and on old rows
the genuinely unpriced buckets still carry their own cost in `unpricedCostAmount` because that half
of the unpriced factory was always correct. **The exposure figure becomes trustworthy immediately,
on historical data, with no recomputation.** Only the *per-priced-bucket residue* on pre-existing
rows is wrong, and it was never read by any shipped reader.

---

## 7. Adjacent findings — reported, not touched

- **`customerVisibleRequiresApproval` defaults to `true`** in
  `V20261005100000__ai_policy_and_governance.sql:298` (shadow parity). Any send gate wired to it
  refuses every reply until a tenant opts out. AI governance, not finance. **Not touched.** Flagged
  to the orchestrator; see §8.
- **`usage_rollup_snapshots_current_bucket_key`** has an inverted predicate: its comment says "only
  the highest generation is indexed for it", but `WHERE supersedesSnapshotId IS NULL` selects
  generation **1**, since `usage_rollup_snapshots_generation_pair_check` ties generation 1 to a null
  pointer. It is still useful as a uniqueness constraint (at most one generation-1 row per bucket),
  and `RollupRepository`'s read does not use it. Schema defect; reported in phase 23, unchanged.
- **`RollupRepository` binds `:projectId` beside a validated organisation parameter.** Judgement
  requested — see §8.

---

## 8. Judgement requested: `RollupRepository`'s `:projectId`

**My assessment: acceptable today, and I recommend leaving it — but not because it is harmless.**

What is true now, and is structural rather than incidental:

- The tenant predicate is bound by `ScopedWhere.of(scope).bindNamed(...)`, which writes the reserved
  `__fe_tenant_organization_id` **last**, so a caller cannot shadow it by passing the same name. The
  organisation half of the key is not a caller-supplied parameter at all.
- Every entry point that reaches these methods goes through `TenantQueryGuard.scope(caller, projectId)`
  in `RollupService`, `FinanceUsageReadService` and `DrillThroughService`, which validates membership
  before the query runs. A caller-provided project id is a request; the guard makes it authorisation.
- The project's own row is tenant-scoped by `usage_rollup_snapshots_projectId_fkey` plus the
  organisation column, so a cross-tenant id cannot produce a cross-tenant row: the `AND` of both
  predicates returns empty, which is the same answer as "no usage".
- The rate-card version and period references are composite tenant foreign keys, so a cross-tenant
  *value* cannot be pinned either.

What is not true, and is why it deserves the ruling rather than a shrug:

- The repository does not enforce the pairing. `WHERE organizationId = :org AND projectId = :p` is
  correct **only** because `:p` was validated upstream. The same class of assumption — "a validated
  value reached me from a trusted caller" — is what a cross-tenant read looks like before it is one.
- The failure mode is silent and plausible: an unvalidated foreign project id yields *zero rows*, which
  is indistinguishable from a project with no usage. A caller that could supply one would get an empty
  finance answer rather than a refusal, and an empty finance answer is a number people quote.
- It is one method away from a caller that is not the one today. `byDimension`,
  `snapshotTotals`, `generation`, `history` and `currentBucket` are all public.

**Recommendation: no change without the orchestrator's ruling**, which is what was asked. If a ruling
is given, the cheap structural version is to make the repository take the already-validated
`TenantScope`'s project rather than a loose `UUID` — i.e. have `guard.scope(caller, projectId)` return
a scope whose `projectId()` the repository reads instead of a parameter — which removes the assumption
without changing a single SQL string. I would not add a redundant membership query inside the
repository: that duplicates the guard, and a second check that can disagree with the first is worse
than one authoritative check. Flagging it as a *latent* risk, not a live defect.

---

## 9. Commands

```
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'
.\gradlew.bat :backend:reporting-finance:test `
  "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"
.\gradlew.bat :backend:reporting-exports:test `
  "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"
```

`:backend:reporting-finance` — **142 tests, 0 failures, 0 errors, 0 skipped** (was 129 before this
phase; +13).
`:backend:reporting-exports` — green, re-run to confirm the coordinating export contract is
unaffected.

No `./gradlew build` was run; the full gate remains the orchestrator's.
