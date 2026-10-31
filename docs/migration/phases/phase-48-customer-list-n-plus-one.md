# Phase 48 — the customer list reads a page, not a row at a time

**The gap this closes.** `GET /api/v1/customers?limit=50` measured **p50 877 ms / p95 2540 ms**
against the PROJECT_REQUIREMENTS section-7 budget of 500 ms, on the 40 000-customer dataset. It was
the only one of the seven performance scenarios still over budget. The database was not the
bottleneck: EXPLAIN (ANALYZE, BUFFERS) put the page query itself at **25 ms** — bitmap heap scan,
top-N heapsort, index-served correlated `conversationCount`.

The cost was the statements that followed it. `CustomerService.toListItem` read the
`latestTicket`, the follow-ups and the appointments **once per row**.

---

## 1. The correction: it was 151 statements, not 51

The measurement that started this phase counted the per-row `latestTicket` calls — one per row, 50
of them, plus the page query, "51 statements". That is **a third of the story**, and acting on it
alone would have left two thirds of the defect in place.

Re-measured with a spy over the real `CustomerRepository`, real PostgreSQL 16 + pgvector container,
50-row page:

| Repository method | Before | After |
| --- | ---: | ---: |
| `list` (the page) | 1 | 1 |
| `latestTicket` (per customer) | **50** | 0 |
| `leads` (per customer) | **50** | 0 |
| `appointments` (per customer) | **50** | 0 |
| `latestTickets` (whole page) | 0 | 1 |
| `leadsFor` (whole page) | 0 | 1 |
| `appointmentsFor` (whole page) | 0 | 1 |
| **Total statements for a 50-row page** | **151** | **4** |

The `1 + 3 × limit` shape is `CustomerService.toListItem` and is now the whole accounting. Note
that on the performance dataset `tests/performance/dataset.sql` seeds **no `Lead` and no
`appointments` rows at all**, so those 100 statements returned empty sets there — cheap to
execute, still 100 round trips, and still the p95 tail. They are removed for the same reason the
other fifty are.

---

## 2. The fix

### 2.1 `CustomerRepository` — three batched reads, same scope, same rules

| Method | Statement | Shape |
| --- | --- | --- |
| `latestTickets(scope, ids)` | `select distinct on (k."customerId") … order by k."customerId", k."createdAt" desc` | one newest conversation per customer |
| `leadsFor(scope, ids, perCustomerLimit)` | `row_number() over (partition by l."customerId" order by l."createdAt" desc)` filtered by `:perCustomerLimit` | up to five leads per customer |
| `appointmentsFor(scope, ids, perCustomerLimit)` | same, ordered by `a."appointmentDate" desc` | up to five appointments per customer |

Each returns a `Map<UUID, …>` keyed by customer id. **A customer with no rows is absent from the
map**, not present with an empty value — "there is nothing to show" and "it was not loaded" stay
different facts.

**Why `DISTINCT ON` for the ticket and `row_number()` for the children.** The ticket projection is
one row per customer, and `DISTINCT ON` requires the sort to lead with the partitioned expression,
so "newest wins" *is* the sort rather than a post-processing step that could disagree with it. The
child projections are up to five rows per customer, and a statement-wide `limit` there would
truncate **customers**, not rows — a page of 50 whose first customer has five leads would return
five customers and forty-five empty lists. So the bound is a window function inside the partition,
the same shape `ClinicRepository.FUNNEL_BY_STATUS` already uses, and `:perCustomerLimit` is a
**bound parameter**, never spliced text.

**Why the two window-function statements are not built through `scope.select`.** That helper appends
the tenant predicate to the statement's *own* `WHERE`, and here the outer `WHERE` is the
per-customer bound, not the tenant. The predicate is still `scope.predicate(alias)` and nothing
else, substituted with `String.formatted` so the statement text is read once — the idiom
`ClinicRepository` documents. It is applied **inside** the subquery, so `row_number()` counts
in-scope rows only.

**An empty id list reads nothing.** Each method returns an empty map before building a statement.
Binding `in ()` is a PostgreSQL syntax error, which would be a 500 on exactly the request that
most needs a quiet, empty, authorized answer.

### 2.2 `CustomerService` — one read for the page, then a map lookup per row

`loadPageProjection` reads all three projections for the whole page once, before any row is
projected. `toListItem` then does a map lookup instead of a query. The projection is a private
record; the per-row reads stay exactly where they were.

**`latestTicket(scope, customerId)` is kept on purpose.** It is the definition the batched
statement is proven equal to. Deleting it as "now unused" would leave the batched ordering with
nothing to be compared against — the condition under which an optimisation quietly stops being
equivalent. `leads` and `appointments` are still the 360's own path.

### 2.3 `CrmQueryScope.Statement` — an order-by with no limit

`orderedBy(orderBy, fetchLimit)` is unchanged for callers and now delegates to a new
`orderedBy(orderBy)` plus `withLimit(n)`. `DISTINCT ON` needs the sort without a limit, and the
concatenated statement the window-function shape needs one appended after the fact.

---

## 3. Semantics did not move

Same fields (`id`, `ticketNumber`, `createdAt`, `status`, `subject`), same "newest conversation
wins", same null-when-absent, same scope, same ordering, same per-customer cap of five.

- **Tenant/project scope** comes from `CrmQueryScope` in all three batched statements, never from a
  caller-supplied value. The id list narrows an already-scoped set; it cannot widen one.
- **Ties** stay as unspecified as before. The batched statements use character-for-character the
  same `order by` expression the per-row queries used and add no tie-break the original lacked.
- **The published shape is untouched.** No DTO, no OpenAPI source, no `CustomerListItem` field.
  `LegacyFixtureComparisonTest` (6 tests, including the `latestTicket.ticketNumber ==
  "#0814000000001"` fixture comparison) passes unchanged, which is the ADR 0016/0017 check that
  matters for this surface.

---

## 4. What is asserted, and why none of it is a timing assertion

A latency assertion would be flaky on a shared machine and would pass for the wrong reason often
enough to be worthless. What is asserted is the property that **caused** the latency.

### 4.1 Equivalence — `CustomerListProjectionIntegrationTest` › *the batched projections are the per-row projections* (9)

For the same rows, the batched projections equal the per-row ones, customer by customer, asserted
on the DTO the API actually publishes and recomputed independently from `latestTicket`,
`leads` and `appointments`:

- three conversations for one customer, one for another, none for a third — the batch equals the
  per-row map, and the customer with no conversation is **absent from the map**, not blank in it;
- the published `CustomerListItem.latestTicket` equals the per-row projection for **every** item
  on a 50-row page, including the null one;
- seven leads for one customer, so the bound of five is a bound: the batch is the same five in
  the same `createdAt desc` order; same for appointments by `appointmentDate desc`;
- the published `followUps` and `appointments` equal the per-row projections for every item;
- scope: an id from a project the caller cannot reach yields nothing from the batch, **with a
  positive control** on a project the caller can reach — and the same control for another tenant's
  id, so an all-empty result cannot satisfy the refusal;
- an empty id list reads nothing rather than asking `in ()`.

### 4.2 The load-bearing structural assertion — `…` › *a page is read once, not once per row* (4)

A Mockito spy over the real repository. This is what makes the change provably real rather than
plausibly real:

- `a 50-row page asks the batch once and the per-row query never` —
  `verify(times(1)).latestTickets`, `verify(never()).latestTicket`, and the same for leads and
  appointments;
- `the statement count is the same for a 5-row page and a 50-row page` — **4 and 4**. Ten times the
  customers, the same number of statements. A per-row read fails here even when every row is
  cached;
- `the batch is handed exactly the ids on the page, once` — the batch receives the page's ids, not
  a subset, not a superset;
- `a page with no customers issues the page query and nothing else` — 1 statement, and the
  no-membership principal still gets a quiet empty page.

### 4.3 Red to green

Reverted `CustomerService.java` alone — the batched statements stay, so the suite still compiles —
and reran. Exactly **three** tests went red, and the failures print the defect:

```
the statement count is the same for a 5-row page and a 50-row page FAILED
    expected: 151   but was: 16
the batch is handed exactly the ids on the page, once FAILED
    Wanted but not invoked: customerRepository.latestTickets(<any>, <any List>);
    However, there were exactly 151 interactions with this mock
a 50-row page asks the batch once and the per-row query never FAILED
    Wanted but not invoked: customerRepository.latestTickets(<any>, <any List>);
    However, there were exactly 151 interactions with this mock
```

16 is `1 + 3 × 5` — the 5-row page. 151 is `1 + 3 × 50`. The equivalence and scope suites stayed
green throughout, which is the point: they are invariants, and only the shape assertions are
supposed to move. Restored: **13/13 green**.

---

## 5. Suite registration

All tests live in `@Nested` classes, so per the `containerClassNote` in
`tests/contracts/expected-suites.json` the container is **not** registered and the two nested
suites are. (The container emitted its own XML on some runs and not others, exactly as that note
describes.)

| suite | reportName | module | stage | tests |
| --- | --- | --- | --- | ---: |
| `com.firstelement.crm.CustomerListProjectionIntegrationTest$BatchedProjectionsMatchThePerRowQueries` | the batched projections are the per-row projections | crm-core | backend-integration | 9 |
| `com.firstelement.crm.CustomerListProjectionIntegrationTest$APageIsReadOnceNotOncePerRow` | a page is read once, not once per row | crm-core | backend-integration | 4 |

---

## 6. Found, and deliberately NOT fixed here

**`CustomerDetail` reads `leads(scope, id, 0)` and `appointments(scope, id, 0)`.**
`Statement.orderedBy(orderBy, 0)` appends `limit 0`, and PostgreSQL's `LIMIT 0` returns no rows.
So the customer 360's `followUps` and `appointments` are **always empty**, for every customer,
today. The fixture holds no leads and no appointments and no test asserts either, so it passes.

This is pre-existing, it is not the N+1, and fixing it **changes the published 360 body** — which
is ADR 0016/0017 territory, not a performance fix. It is reported, not silently repaired.
`toDetail` is untouched by this phase and still passes `0`; the list path still passes `5`, exactly
as before. The list/360 disagreement therefore survives this phase unchanged rather than being
resolved by accident.

---

## 7. Commands

```
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'

.\gradlew.bat :backend:crm-core:test --tests "com.firstelement.crm.CustomerListProjectionIntegrationTest"
.\gradlew.bat :backend:crm-core:test :backend:inbox-helpdesk:test
.\gradlew.bat compileTestJava
```

crm-core 139 tests, inbox-helpdesk 123 tests, **262 total, 0 failures, 0 skipped**, against real
Testcontainers PostgreSQL 16 / pgvector. Every module in the repository compiles, test sources
included. No Docker-unreachable skip path: the container failing to start fails the build.

The `firstelement` database was never touched; Testcontainers only.