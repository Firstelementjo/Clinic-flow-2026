# Phase 15 — the inbox work queue

**Defect.** `InboxFilterDefinition` accepted only `assigned-to-me` and `history`, and the inbox
list **defaulted to `assigned-to-me`**. That view's involvement test is *"assigned to an employee
whose user is me, **or** I was the last human replier"*. A brand-new, unassigned conversation
satisfies neither branch, so **the product's primary screen could not show the work queue at all**.
The work existed; the only way to see it was to navigate to `/inbox/unassigned` and know to look
there. Phase 11's Angular inbox found this and could not fix it client-side.

**Status: delivered.** Three new wire values, an enum in the OpenAPI document, counts for every
value, and one definition of "unassigned" shared by both routes that can answer it.

**No migration.** No schema, data or `ops/flyway/**` change. Nothing for the database agent.

---

## 1. The wire vocabulary — the final answer

Declared once in `InboxFilter` (`backend/crm-core/.../domain/InboxFilter.java`). Declaration order
is the tab order, because `savedViewCounts` returns a `LinkedHashMap` and a client that lays out a
segmented control in map order gets this order for free.

| wire value | Java constant | meaning |
| --- | --- | --- |
| `unassigned` | `UNASSIGNED` | OPEN or PENDING with **no assignee and no involvement test at all** — the work queue |
| `all-open` | `ALL_OPEN` | OPEN or PENDING, anyone's — a supervisor's whole load |
| `assigned-to-others` | `ASSIGNED_TO_OTHERS` | OPEN or PENDING assigned to an employee who is **not** the caller |
| `assigned-to-me` | `ASSIGNED_TO_ME` | unchanged: OPEN/PENDING and involved (assigned to me, **or** I last replied) |
| `history` | `HISTORY` | unchanged: the same involvement test, restricted to RESOLVED/CLOSED |

Two boundaries that are decisions rather than consequences:

- **`unassigned` has no involvement test.** "Unassigned" is a statement about *ownership*. Adding
  "…and the caller was not the last replier" would hide a conversation from the work queue for the
  very person who last touched it. Negative control **NC-G** re-adds that clause and 14 tests go red.
- **`assigned-to-others` requires an assignee** (`e."userId" is not null`). An unowned conversation
  belongs to the queue, not to a peer's load, so it is excluded. `assigned-to-me`, `assigned-to-others`
  and `unassigned` therefore do **not** partition the open set, and that is deliberate: `assigned-to-me`
  is *broader* than "assigned to me" because of the last-replier clause. Narrowing it would change
  what employees already see.

**The default is still `assigned-to-me`.** See §6 — it is a product decision and it is not mine.

---

## 2. `filter` is declared as an `enum` — and why the parameter is still a `String`

The served document now publishes:

```json
{ "name": "filter", "in": "query", "required": false,
  "description": "The saved view. One of [unassigned, all-open, assigned-to-others, assigned-to-me, history]. Absent means 'assigned-to-me'.",
  "schema": { "type": "string",
              "enum": ["unassigned","all-open","assigned-to-others","assigned-to-me","history"] },
  "example": "unassigned" }
```

### The obvious implementation is wrong, and it was measured, not reasoned about

The obvious way to get an enum into the document is to type the parameter as the enum. That was the
first implementation. It was **rejected on measurement**:

```
schema: { "type": "string",
          "enum": ["UNASSIGNED","ALL_OPEN","ASSIGNED_TO_OTHERS","ASSIGNED_TO_ME","HISTORY"] }
```

Two defects in one change:

1. springdoc renders a typed enum parameter as its **Java constant names**, so the contract would
   have advertised `UNASSIGNED` — a vocabulary that is not the wire vocabulary this API has always
   used, and exactly the "substitute your own naming" the brief forbade.
2. Spring binds an enum parameter **by constant name**. `?filter=assigned-to-me` — what the legacy
   client has always sent — would have become a **400**. The compatibility surface broken by a
   documentation fix.

So the parameter stays a `String` (binding untouched, trimming preserved, no new converter) and the
**document** carries the constraint, which is where the constraint was actually missing. The values
come from `InboxFilter.acceptedValues()`, so the documented list is derived from the one type the
server accepts and cannot drift from it. An `@Schema(allowableValues = {"a","b"})` would have been
the shorter spelling and would have been a second list to keep in step — the same failure mode as the
hand-written two-filter loop in `/inbox/counts` that this change had to fix.

### The bug I shipped first, and how it was found

The first working version mutated the `filter` parameter's schema **in place**. That is wrong, and
it was wrong in a way no unit test would have found: **springdoc hands out shared model objects.**
The `Schema` instance behind `filter` is the same instance behind `projectId` on `/notes`,
`resourceType` on `/notes`, and every other query parameter in the document. Setting an enum on it
stamped the inbox vocabulary onto unrelated parameters across the whole contract — five paths
corrupted, none of them about the inbox, and the exported document still built successfully.

It was caught only because the promotion diff was compared **path by path** rather than skimmed, and
because `tests/contracts/openapi.json` was compared against the served document rather than assumed
equal. `InboxFilterContractTest.noOtherParameterIsContaminated` now reproduces the shared-instance
condition deliberately (one `StringSchema` handed to four parameters) and counts enum-bearing
parameters across the **whole** document. Negative control **NC-H** re-installs the mutate-in-place
implementation and that test fails with `projectId must stay an unconstrained string`.

The fixed version touches nothing reachable from another operation: a fresh `Parameter` and a fresh
`StringSchema` are built and the **list entry** is swapped; the old objects are left exactly as they
were for whoever else holds them.

### The customiser throws rather than doing nothing

If the path, the GET, or the `filter` parameter is missing, the customiser raises
`IllegalStateException` rather than skipping. A silently-skipped customiser is an enum that vanished
on a controller rename and nobody would know until a client sent a value the server refused. The
document is rebuilt on every request to `/api/v1/openapi`, and promotion is blocking, so failing the
export is the right severity.

### Layering debt, stated plainly

`InboxFilterEnumOpenApiCustomizer` lives in `inbox-helpdesk` because that is where the vocabulary
lives, and because `backend/app-bootstrap` — the module that owns the contract and the export test —
is **outside this change's ownership globs**. That required declaring springdoc on
`backend/inbox-helpdesk/build.gradle.kts`, pinned to the **same** `rootProject.extra["springdocVersion"]`
property `app-bootstrap` uses, so the two resolve to one artifact and Gradle deduplicates. A domain
module depending on the API-documenting library is the wrong layering. **F-15-1**: move the file to
`app-bootstrap` when ownership allows; it is a one-file move and the behaviour is unchanged.

---

## 3. `/inbox/counts` widened in the same change

`InboxQueryService.savedViewCounts` iterated a hand-written two-element array. The moment the list
could filter three more ways, a client rendering a segmented control would have shown a **permanent
`0`** for each new tab — a number that is not wrong, only missing, which is how a broken product
looks rather than how a broken endpoint announces itself.

The loop is now `for (InboxFilter filter : InboxFilter.values())`. The omission cannot be
reintroduced: a filter cannot reach the list without also reaching the counts. The test asserts **key
set equality** against `InboxFilter.acceptedValues()` and then, for every value, that the count
equals the size of the list it labels — so a badge can never contradict its own tab.

`/inbox/counts` takes no `filter` parameter: it publishes *all* of them. There is nothing there to
refuse.

---

## 4. Reconciliation: `?filter=unassigned` is canonical

**Decision: the `unassigned` FILTER is canonical.** It is in the wire vocabulary, it is counted by
`/inbox/counts`, and it is what the OpenAPI document publishes. The legacy
`GET /inbox/unassigned` route is kept — Phase 11's journeys and the legacy client call it.

It cannot become a full alias for the list, because its response is **richer**: a per-row diagnosis
(`reason`, `reasonDetail`, `waitingSeconds`, `signals`) and queue `totals`, for which the list
endpoint has no column. Making the route call the list would break those journeys.

What it does instead is take its **membership rule** by reference:

```java
// UnassignedQueueRepository
public static final String UNASSIGNED_ACTIVE = InboxFilterDefinition.UNASSIGNED_ACTIVE;
```

One definition, two routes. A client cannot get two different answers to "what is unassigned"
because the predicate that decides membership is one constant.

This is asserted, not described. `theLegacyRouteAndTheFilterReturnTheSameSet` compares the two sets
in the service layer **and** over the wire. It carries a **PENDING** and a **RESOLVED** unassigned row
for a reason found by negative control:

> **NC-B was STILL GREEN on the first rehearsal.** The mutation gave `/inbox/unassigned` its own
> definition again — one that quietly dropped `PENDING` — and the reconciliation test passed. The
> fixture held only OPEN rows, so the two definitions could not disagree. That is a real gap in the
> test, not a flaw in the mutation: a reconciliation assertion is only as strong as the states that
> make the two sides distinguishable. Fixed by adding the PENDING row; NC-B now goes red.

---

## 5. Refusals

An unaccepted `filter` value is a **400 `VALIDATION_FAILED`**, never a 500. Two things make that
true:

- `ConversationsController.parseFilter` is the single parse point and raises `ValidationFailedException`.
  It now names the accepted values in the message **and** in `details.accepted`, so a caller is told
  what it may send rather than only what it may not.
- `CrmExceptionAdvice` (crm-core, which already covers `com.firstelement.inbox`) gained a handler for
  `MethodArgumentTypeMismatchException`, so a query parameter Spring cannot bind is rendered as a 400
  rather than an unhandled 500. It names the accepted values **only when the target type is an enum**,
  and otherwise says "`x` is not a valid value" — so the handler cannot become an oracle for probing
  accepted inputs on other parameters.

Asserted over HTTP for `everything`, `UNASSIGNED`, `Assigned-To-Me` and `unassigned,history`: status
exactly `400`, code `VALIDATION_FAILED`, `retryable: false`, and the standard `ErrorResponse` key set.
Negative control **NC-E** makes the parse throw `IllegalStateException` instead, and both refusal
tests go red.

Case-sensitivity is deliberate. `?filter=UNASSIGNED` is a **400**: the documented vocabulary and the
accepted vocabulary are the same list, and the document publishes the kebab-case wire values only.

---

## 6. The default is unchanged — a product decision for the orchestrator

**Kept: `assigned-to-me`.** Not a bug fix and not mine to make. It is the legacy client's behaviour,
which is the compatibility surface, and changing it changes what every employee sees on every screen
refresh.

**The argument for changing it, which I am not acting on.** Defaulting to the work queue is
plausibly the *right* default for a helpdesk. The inbox is the screen an employee opens to see what
is waiting for them, and today that screen shows them only threads they are already involved in —
so a freshly-arrived customer message is invisible until someone navigates to a second URL. There is
also a second-order effect: with `assigned-to-me` as the default, an employee's inbox is empty on
arrival, which reads as "no work" rather than as "no work is mine".

**The argument against.** It is a visible behaviour change for every user on every refresh, it is not
a defect in the existing filter, and the Angular client was built against the current default.
Switching it needs the Phase 11 journeys re-checked and a deliberate release note.

**If you rule for it**, the change is one line: `ConversationsController.defaultFilter()` returns
`InboxFilter.UNASSIGNED`. It is a single named method precisely so that it is a one-line decision
rather than a search. `InboxFilterContractTest.theDefaultHasNotSilentlyChanged` and
`InboxWorkQueueIntegrationTest.the HTTP default is still assigned-to-me` currently **fail** on that
change (negative controls **NC-D**, verified in both suites), so the decision cannot be made
accidentally and a later silent regression is caught.

---

## 7. Tenant and project scope

No new query path was invented; all three views go through the existing
`ConversationRepository.inbox` / `countWithFilter`, which always AND `CrmQueryScope` and always add
the `employees` join. Every filter fragment remains tenant-column-free, which
`InboxFilterDefinitionTest.the fragment never mentions a tenant column` checks for **all five**
values, not just the three new ones.

Proven, per view:

- **Cross-tenant.** `another tenant's rows never appear in any view, new or legacy` seeds two
  tenant-B rows (one unowned, one owned) and asserts for **all five** filters that neither appears in
  tenant A, and that tenant A's five rows never appear in tenant B — then asserts tenant B *does*
  see its own two, so the negative is not vacuous. The legacy queue is checked in the same test.
- **Out-of-scope project.** `an out-of-scope project is refused before a statement exists` asserts
  `PolicyDeniedException` from both `inbox.inbox` **and** `inbox.savedViewCounts` for each new
  filter — the counts build the same scope, so they must refuse before they count anything.
- **Non-disclosure.** For each new filter, the 404 body for an out-of-scope project is compared field
  by field against a nonexistent project id: `code`, `message` and `details` must be identical. A
  denial that said more than a nonexistent id would make the status code an existence oracle.
- **No membership.** A principal with no membership row sees an empty set under all three new views
  and **zero** on every badge.

No synchronous AI or external provider call was introduced; no HTTP mutation was touched.

---

## 8. Tests

### Command (IPv4 is mandatory on this host)

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'
$env:GRADLE_OPTS='-Djava.net.preferIPv4Stack=true'
.\gradlew.bat :backend:crm-core:test :backend:inbox-helpdesk:test --rerun-tasks --no-build-cache --no-daemon --console=plain `
  '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true'
```

### Exact counts

`:backend:crm-core:test` — **10 suites, 125 tests, 0 failures, 0 errors, 0 skipped**

| suite | tests |
| --- | --- |
| crm.api.TimestampsTest | 7 |
| crm.casework.CaseSolutionMatchingTest | 6 |
| crm.CrmCoreIntegrationTest | 33 |
| crm.CustomerReferenceNamespaceIntegrationTest | 15 |
| crm.domain.TicketNumberTest | 8 |
| crm.error.CrmDomainExceptionTest | 8 |
| crm.query.CrmQueryScopeTest | 11 |
| crm.query.CursorPageTest | 7 |
| **crm.query.InboxFilterDefinitionTest** | **17** (was 10) |
| crm.timeline.TimelineEventMapperTest | 13 |

`:backend:inbox-helpdesk:test` — **5 suites, 88 tests, 0 failures, 0 errors, 0 skipped**

| suite | tests |
| --- | --- |
| **inbox.InboxFilterContractTest** | **8** (new) |
| inbox.InboxHttpIntegrationTest | 17 |
| inbox.InboxJourneyIntegrationTest | 37 |
| **inbox.InboxWorkQueueIntegrationTest** | **20** (new) |
| inbox.LegacyFixtureComparisonTest | 6 |

**Combined: 15 suites, 213 tests, 0 failures, 0 errors, 0 skipped.**

`InboxFilterContractTest` needs **neither Spring nor Docker**, deliberately: it closes a *contract*
defect, and a contract assertion that needs PostgreSQL is one that can go unrun on a machine without
it. The whole-document enum census also lives there, so the shared-`Schema` trap is guarded without a
database in the loop.

### Coverage of the seven required assertions

| # | required | test |
| --- | --- | --- |
| 1 | unassigned appears under `unassigned`, and under neither `assigned-to-me` nor `history` | `aNewUnassignedConversationIsInTheWorkQueueAndNowhereElse` |
| 2 | `all-open` returns every open conversation regardless of assignee | `allOpenIgnoresOwnership` |
| 3 | `assigned-to-others` returns peers' threads and excludes the caller's own | `assignedToOthersExcludesTheCallersOwn` (asserted **both** directions) |
| 4 | `/inbox/counts` key set **equals** the list's vocabulary | `countsPublishAKeyForEveryFilter` (+ every count == its list size) |
| 5 | an unknown value is refused, not 500 | `unknownFilterIsRefusedNotFiveHundred`, `theRefusalCarriesTheAcceptedValues` |
| 6 | cross-tenant and out-of-scope negatives for every new filter | `noNewViewCrossesATenant`, `outOfScopeProjectIsRefusedBeforeTheQuery`, `outOfScopeRefusalIsNonDisclosing`, `noMembershipSeesNoQueue` |
| 7 | `/inbox/unassigned` == the `unassigned` filter | `theLegacyRouteAndTheFilterReturnTheSameSet` (service **and** wire) |

Plus: `theQueueIsOpenOrPendingOnly`, `assignmentMovesTheRowFromTheQueueToItsOwner`,
`assignedToOthersIsOpenOnly`, `countsIncludeTheNewViews`, `countsAgreeWithTheLegacyQueueTotal`,
`bothRoutesAreEmptyWithoutScope`, `channelNarrowsEveryNewView`, `searchNarrowsEveryNewView`,
`theDefaultIsUnchanged`.

### Negative controls — all eight go red, all files restored byte-identically

Each control re-introduces one specific thing this change fixed, asserts on the **state after the
action** (the test outcome) rather than on a printed message, and restores by SHA-256.

| id | mutation | red |
| --- | --- | --- |
| NC-A | the document customiser becomes a no-op | 5 of 7 |
| NC-B | `/inbox/unassigned` gets its own definition again, dropping PENDING | 1 of 20 |
| NC-C | `/inbox/counts` names two filters explicitly again | 4 of 20 |
| NC-D | the default switches to `unassigned` | 1 of 20, **and** `theDefaultHasNotSilentlyChanged` in a separate run |
| NC-E | an unaccepted value throws instead of refusing | 2 of 20 |
| NC-F | `assigned-to-others` forgets the caller | 3 of 37 |
| NC-G | the work queue grows an involvement test | 14 of 37 |
| NC-H | the customiser mutates the shared `Schema` in place | `noOtherParameterIsContaminated`, `theDocumentStatesTheDefault` |

### A harness lesson worth keeping

The first rehearsal reported **all seven controls STILL GREEN**. The cause was mine: the harness
called `gradlew.bat --tests X` with **no task name**, so Gradle printed its help text and exited 0. A
rehearsal that cannot fail is worse than no rehearsal. The harness now (a) names the task and
(b) **refuses to report a result** unless it can see test-result lines, reporting `HARNESS-FAILED`
otherwise. Two further controls were malformed in ways only that guard caught: NC-E originally left
a dangling argument list and did not compile, and NC-D's second Gradle invocation silently produced
no result lines and had to be re-run standalone before its evidence was accepted.

---

## 9. Promotion — the orchestrator's act

`tests/contracts/openapi.json` is **not** mine and has not been touched. The served document was
exported and compared path by path.

**Exactly one path differs semantically:** `GET /api/v1/conversations`, whose `filter` parameter gains
the description, the five-value `enum` and the example. A whole-document sweep confirms **one**
parameter in the entire contract carries an enum, and it is `filter`.

Four component schemas also differ — `SaveClassificationQuestionRequest`, `Money`, `TenantContext`,
`CsrfToken` — in **property order only**. Same properties, same types, different order: JDK hash
iteration order in springdoc's schema generation. Unrelated to this change, semantically inert, but a
byte-diff-based promotion check will show them, so it is named here rather than discovered later.

Promote with:

```powershell
ops\quality\checks\export-openapi-document.ps1
```

## 10. Suite entries for `tests/contracts/expected-suites.json`

```json
{"suite":"com.firstelement.inbox.InboxFilterContractTest","module":"inbox-helpdesk","stage":"backend-integration","minTests":8,"kind":"contract","services":[],"note":"Phase 15: the filter parameter is published as an enum of the five WIRE values (unassigned, all-open, assigned-to-others, assigned-to-me, history), never the Java constant names; the documented list and the parser are the same list; the default is asserted to still be assigned-to-me; and a whole-document census proves no OTHER parameter gains an enum -- springdoc shares Schema instances, so mutating filter's schema in place corrupted five unrelated paths. Needs neither Spring nor Docker."},
{"suite":"com.firstelement.inbox.InboxWorkQueueIntegrationTest","module":"inbox-helpdesk","stage":"backend-integration","minTests":20,"kind":"integration","services":["postgresql","pgvector"],"note":"Phase 15: the defect -- a new unassigned conversation appears under unassigned and under neither assigned-to-me nor history; all-open returns every open row whoever owns it; assigned-to-others excludes the caller asserted both directions; /inbox/counts key set EQUALS the list vocabulary and every count matches its list; an unknown value is a 400 refusal naming the accepted values, never a 500; cross-tenant negatives for all five filters with the positive case asserted so they are not vacuous; an out-of-scope project is refused by the guard before the statement exists and the 404 body is identical to a nonexistent project; and /inbox/unassigned returns the SAME set as the unassigned filter, asserted in the service layer and over the wire."}
```

No existing `minTests` moves. `CrmCoreIntegrationTest` stays 33; `InboxJourneyIntegrationTest` stays
37; `InboxHttpIntegrationTest` stays 17. `InboxFilterDefinitionTest` grew 10 → 17 and, under the
`minTests`-is-a-reviewed-floor semantics, needs no entry change.

## 11. Follow-ups

- **F-15-1** — move `InboxFilterEnumOpenApiCustomizer` to `backend/app-bootstrap`, beside the
  contract it publishes, and drop the springdoc dependency from `inbox-helpdesk/build.gradle.kts`.
  One-file move; behaviour unchanged. **Blocked only by ownership, not by design.**
- **F-15-2** — the default-filter product decision in §6. Not a defect; needs your ruling.
- **F-15-3** — `packages/messaging/src/identity-resolver.ts` and `packages/shared/src/ticketing.ts`
  still carry the legacy `count(*)` + `generateCxId` customer-reference path (carried over from
  Phase 14). Outside these globs; unaddressed by me.
- **F-15-4** — `savedViewCounts` runs one `COUNT` per view, so a five-tab control is five queries
  per badge refresh. Correct and scoped; worth a cached count column if the inbox ever carries a
  few thousand open rows per tenant. Not a defect at current volumes.
- **F-15-5** — the four component schemas that reorder on every export suggest the committed
  document is byte-unstable across JDKs. Cosmetic, but a promotion gate that diffs bytes will keep
  reporting a phantom change. Consider sorting keys in `export-openapi-document.ps1`. That script is
  `ops/**` — not mine.