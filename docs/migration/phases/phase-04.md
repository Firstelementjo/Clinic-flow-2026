# Phase 4 — CRM/helpdesk and the unified inbox

## Objective

Move the highest-value user workflow — customer records, the unified inbox, the conversation
thread, internal notes, cases, assignment and the timeline — from the legacy NestJS runtime to
Spring **without changing behaviour**, and keep a `/api/v1` surface the current client can call
during the strangler window.

No legacy Node code was deleted, no migration was added or applied, and no file outside the
ownership globs was changed.

## Scope (owned by Agent D)

`backend/crm-core/**`, `backend/inbox-helpdesk/**`, module registration in `settings.gradle.kts`,
project dependencies in `backend/app-bootstrap/build.gradle.kts`, and this file.

Not touched: `backend/shared-kernel/**`, `backend/tenant-security/**`,
`backend/identity-access/**`, `backend/database/**`, `ops/**`, `apps/**`, `packages/**`,
`backend/app-bootstrap/src/**`.

## Delivered

### `backend/crm-core` — the CRM model (74 production classes)

| Concern | Type | Notes |
| --- | --- | --- |
| Domain vocabulary | 12 enums | `ChannelType`, `ConversationStatus/Mode/Priority`, `MessageDirection/AuthorType/DeliveryStatus`, `ConversationEventType`, `CaseOutcome`, `CustomerLocale`, `InboxFilter`. Wire values are the database values, byte for byte. |
| Ticket / CX numbering | `TicketNumber` | `#MMDD` + 9 digits, `#33` + 6 digits, ASCII padding (a locale-formatted number would break the unique index). |
| Tenant/project predicate | `CrmQueryScope` | Always `organizationId`; either one pinned project or the principal's accessible set. No public constructor — only `TenantQueryGuard` can produce one. |
| Saved-view filters | `InboxFilterDefinition` | A pure port of `inbox-filter.ts`. Never returns a tenant column. |
| Keyset pagination | `CursorPage` | `limit + 1` over-fetch, cursor = the last returned row, `null` on the last page. |
| Customers | `CustomerRepository`, `CustomerService` | List, 360 view, profile update, optimistic versioning, audit. |
| Identity resolution | `IdentityResolver`, `InboundIdentity`, `ResolvedCustomer` | The single definition of "who is this person", reachable from a request and from a worker. |
| Notes | `NoteRepository`, `NoteService` | Four legacy response shapes, modelled separately rather than as one nullable record. |
| Cases | `CaseRepository`, `CaseService`, `CaseSolutionRetriever`, `CaseSolutionMappingRetriever` | Questions, classification, resolution, close/reopen precondition. |
| Timeline | `CrmDomainEvent`, `TimelineEventMapper`, `ConversationTimelineStore` | Append-only. No update, no delete. The mapper is pure and unit-tested. |
| Audit | `CrmAuditRecord`, `CrmAuditWriter` | `REQUIRES_NEW`, never throws, field names only. |
| Errors | `CrmDomainException` + 3 subclasses + `CrmExceptionAdvice` | 404 / 400 / 409 in the Phase-1 `ErrorResponse` shape. |

### `backend/inbox-helpdesk` — the inbox slice (39 production classes)

| Concern | Type | Notes |
| --- | --- | --- |
| State machine | `ConversationService` | status / priority / subject / mode, assignment, close-and-unassign, case close/reopen, transcript. |
| Replies | `ReplyService` (inside `ConversationService`) | Persist-then-send, `Idempotency-Key`, append-only collision reporting. |
| Inbound pipeline | `InboundMessageService` | A port of `inbound-pipeline.ts`: resolve identity → find or create thread → store idempotently → publish. |
| Inbox query model | `InboxQueryService`, `InboxQuery` | Scope, saved view, channel, search, keyset cursor, saved-view counts. |
| Unassigned queue | `UnassignedQueueRepository`, `UnassignedQueuePage` | Membership rule written once; each row explained from facts already on the thread. **No router.** |
| Customer-facing view | `CustomerTranscript` | A separate query, not a filtered copy of the internal one. |
| HTTP | 8 controllers | `/api/v1` compatibility surface plus the routes Phase 4 adds. |
| Dev simulator | `MockInboundController`, `InboxProperties`, `MockChannelProbe` | The legacy `/dev/mock` surface with its two gates, and a startup refusal in production. |

### Endpoints

Legacy routes, body and status unchanged:

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/api/v1/customers` | `CUSTOMER_READ` |
| GET | `/api/v1/customers/{customerId}` | `CUSTOMER_READ` |
| GET | `/api/v1/customers/cx/{cxId}` | `CUSTOMER_READ` |
| PATCH | `/api/v1/customers/{customerId}` | `CUSTOMER_UPDATE` |
| GET | `/api/v1/conversations` | `CONVERSATION_READ` |
| GET | `/api/v1/conversations/{id}` | `CONVERSATION_READ` |
| POST | `/api/v1/conversations/{id}/messages` | `CONVERSATION_REPLY` |
| PATCH | `/api/v1/conversations/{id}/assignment` | `CONVERSATION_ASSIGN` |
| PATCH | `/api/v1/conversations/{id}` | `CONVERSATION_RESOLVE` |
| PATCH | `/api/v1/conversations/{id}/close` | `CONVERSATION_RESOLVE` |
| GET/PUT | `/api/v1/conversations/{id}/classification` | `CONVERSATION_READ` / `CONVERSATION_REPLY` |
| GET/PUT | `/api/v1/conversations/{id}/resolution` | `CONVERSATION_READ` / `CONVERSATION_RESOLVE` |
| POST | `/api/v1/conversations/{id}/close` | `CONVERSATION_RESOLVE` |
| POST | `/api/v1/conversations/{id}/reopen` | `CONVERSATION_RESOLVE` |
| GET/POST/PUT/DELETE | `/api/v1/projects/{projectId}/classification-questions[/{questionId}]` | `CONVERSATION_READ` / `ESCALATION_RULE_MANAGE` |
| GET | `/api/v1/notes`, `/api/v1/notes/project` | `NOTE_READ` |
| POST/PATCH/DELETE | `/api/v1/notes[/{id}]` | `NOTE_CREATE/UPDATE/DELETE` |

Added by this phase:

| Method | Path | Why |
| --- | --- | --- |
| PATCH | `/api/v1/conversations/{id}/mode` | The guarded AI⇄HUMAN transition was worker-only in the legacy runtime. The acceptance journey needs it reachable. |
| GET | `/api/v1/conversations/{id}/transcript` | FR-3.4's history separation has to be observable, and a query flag on one object is how it stops being true. |
| PUT | `/api/v1/assignments/{conversationId}` | PROJECT_REQUIREMENTS §6 names `/assignments` a first-class family. Same service call, same permission, no second code path. |
| GET | `/api/v1/inbox/unassigned`, `/api/v1/inbox/counts` | The explainable queue and the saved-view counts. |
| POST | `/api/v1/customers/resolve-identity` | The resolver was unreachable from HTTP in the legacy runtime. |
| POST | `/api/v1/dev/mock/incoming-message` | The legacy development simulator, with its two gates. |

Wire additions, none of which change a JSON body: `ETag` on reads, `If-Match` on mutations,
`Idempotency-Key` on replies, `X-Idempotent-Replay` and `X-Conversation-Collision` on a reply.

## Key decisions

| Decision | Rationale |
| --- | --- |
| **The version token is `updatedAt`, and `modeVersion` keeps its legacy job** | The schema has exactly one purpose-built counter, `conversations."modeVersion"`, and the legacy code guarded AI⇄HUMAN with it. For status/priority/assignment there is no counter, so `updatedAt` is used: Prisma declared it `@updatedAt`, so every legacy write already bumped it, and `timestamp(3)` gives it millisecond resolution. It is therefore already a monotonic per-row token with **no migration**. A dedicated integer `version` column would be cleaner and needs a database-agent migration — see the follow-ups. |
| **Three different answers to three different races** | A status/priority/subject edit and an assignment collision lose the write (409) because one person would otherwise overwrite another silently. The mode transition is guarded by `modeVersion` because that is what stops an in-flight AI send undoing a takeover. A reply has **no** version check: refusing a reply because a colleague answered first would lose a customer communication, so the reply is written and the thread movement is reported in a header. |
| **`If-Match` is optional** | Absent means last-writer-wins, which is the legacy behaviour, so an existing client that has not adopted it is unaffected. Present means a stale token is 409. |
| **Idempotency lives in `messages.metadata->>'idempotencyKey'`** | There is no `idempotency_records` table in the live schema and no migration was permitted. A reply is per-message, tenant-private and already has a `jsonb` column for attributes, so the key is stored there and looked up through a tenant- and project-scoped statement. A double-clicked Send cannot send a customer two identical messages. |
| **A scoped list returns empty; a scoped delete refuses** | "Not yours" and "not there" both produce no row, but a delete must not silently do nothing — it raises the same 404 a missing row does. |
| **`CursorPage` exposes no `isXxx`/`getXxx`** | Jackson turns those into JSON properties, so `hasMore()`/`isEmpty()` would have added `more` and `empty` to every paged response. Found by the fixture comparison, not by inspection; `CursorPageTest` now asserts the absence. |
| **The unassigned queue explains, it does not route** | Routing is Phase 6. Each entry's reason is derived only from stored facts (`NEVER_ASSIGNED`, `RELEASED`, `REOPENED`) plus the signals that decided it. Making an eligibility or availability judgement here would be the router arriving early and quietly. |
| **The resolution beats an unassignment when both are recorded** | Closing a thread releases its assignee and records the resolution in the same write, so both facts are true at the same instant. The queue's question is "why is this active thread unowned?", and a resolved-then-reopened thread is the case an operator most needs explained. The tie-break is explicit and the signals are returned so it can be checked. |
| **Customer-facing history is a separate query** | Notes are not messages, so they cannot leak into a transcript by construction. Timeline events, author names, delivery failures and `SYSTEM` messages are excluded in the `WHERE`/`SELECT`, not by a response filter, so a future column cannot leak by default. |
| **The note project-wide list is scoped to accessible projects, not the organization** | The legacy service fell back to `organizationScope` when no project was named, which would hand an employee with no memberships every note in the tenant. FR-1.4 forbids it. This is a deliberate strengthening. |
| **The CX-id sequence is tenant-scoped and collisions walk forward** | The legacy `count(customers)` was an unscoped read of another tenant's row count. The sequence is now scoped; because `cxId` is globally unique, a collision walks forward until it finds a free value. The emitted id is still `#33` + six digits and still unique. |
| **A note update needs no new table; a case save is an upsert** | `case_resolutions` and `conversation_classifications` are uniquely indexed by `conversationId`, so a repeated save updates one row — idempotency for the repeatable case writes comes from the index, not from new machinery. |
| **`hasActiveAgent` defaults to `false`** | Phase 4 has no AI module, so new threads start in `HUMAN`. That is the safe direction ("defaulting to HUMAN is the safe direction"). The call site does not change when Phase 10 supplies the `ai_agents` lookup. |

### Legacy behaviour preserved exactly

- **Response shapes**, field for field, including the three that look like mistakes: the 360 view
  keys its lead rows `leads` while the list keys the same rows `followUps`; the 360 view returns
  both `project` and `projectName`; it returns neither `organizationId` nor `createdAt`, because
  the legacy Prisma selection did not. The note routes return four different shapes.
- `lastMessagePreview`: 140 characters then `…`.
- Timeline payload keys and their `null`s; `actorName` is always `null`; a message payload
  carries `bodyLength` and **never** the body; a customer-level event produces no row.
- `toISOString()` rendering: always millisecond precision, always `Z`, and independent of the JVM
  default zone.
- Ticket `#MMDD` + 9 digits, CX `#33` + 6 digits.
- Saved views: `assigned-to-me` is OPEN/PENDING **and** (assigned to me **or** I was the last
  human replier); `history` is the same involvement test over RESOLVED/CLOSED. Default view is
  `assigned-to-me`. Search covers ticket number, subject, CX id and any message body.
- Ordering `lastMessageAt desc, id desc` with a `limit + 1` over-fetch; `nextCursor` is the last
  returned row.
- Workload is **recounted**, never incremented, on both sides of a reassignment and on a status
  change that moves a conversation between active and inactive.
- Per-agent capacity: `activeConversationCount >= maxConcurrentConversations`, and a reassignment
  to the same employee skips the test.
- Closing requires a recorded resolution. Reopening clears `resolvedAt`.
- `firstResponseAt` is captured once, at the moment it happens.
- The inbound identity rule: a new handle is not automatically a new person; a similar name is not
  the same person. Only a verified phone or email links automatically; a name match is reported in
  `possibleMatches` with `reason: "NAME"` and never merged.
- Inbound idempotency is decided by the unique index, not by a preceding `SELECT`.
- Audit action names are the legacy `AuditAction` values; changed **field names** only, never
  values.

### Deliberately different, and why

| Change | Why |
| --- | --- |
| `in ()` → `and 1 = 0` for a no-membership scope | An empty project list is a PostgreSQL **parse error**. The legacy `in []` therefore 500s on exactly the request that most needs a quiet, empty, authorized answer. Found by the container test. |
| Notes without a project are scoped to accessible projects, not the organization | FR-1.4. See above. |
| The CX sequence is tenant-scoped | Removes an unscoped cross-tenant `count`. |
| Bounded forward retries on `cxId` and `ticketNumber` | The legacy global sequence raced; a collision was a failure. Walking forward finds a free value, and the observable numbering is unchanged. |
| Bounded page size is clamped, not rejected | A client asking for 5000 gets 100 instead of a 400. Equally safe, and the response is still complete and cursor-paginated. |
| An out-of-scope cursor is `400 INVALID_CURSOR` | Returning an empty page would tell a client its cursor silently stopped working. |

## Verification (exact commands, Windows, Docker Desktop 29.8.1, JDK 24.0.1)

Full build, every task re-executed, no build cache, no skipped test:

```
.\gradlew.bat build --rerun-tasks --no-build-cache --no-daemon --console=plain
```

→ `BUILD SUCCESSFUL in 3m 50s`, **42 tasks executed**.

Aggregated JUnit XML across every module: **311 tests, 0 failures, 0 errors, 0 skipped.**

| Module | Tests |
| --- | --- |
| `:backend:crm-core:test` | 103 |
| `:backend:inbox-helpdesk:test` | 59 |
| `:backend:tenant-security:test` | 80 |
| `:backend:identity-access:test` | 59 |
| `:backend:database:test` | 3 |
| `:backend:app-bootstrap:test` | 3 |
| `:backend:shared-kernel:test` | 3 |
| `:backend:worker-runtime:test` | 1 |

Per suite:

| Suite | Tests | Database |
| --- | --- | --- |
| `InboxJourneyIntegrationTest` | 36 | Testcontainers pgvector/PostgreSQL 16 |
| `InboxHttpIntegrationTest` | 17 | Testcontainers pgvector/PostgreSQL 16 |
| `LegacyFixtureComparisonTest` | 6 | Testcontainers pgvector/PostgreSQL 16 |
| `CrmCoreIntegrationTest` | 33 | Testcontainers pgvector/PostgreSQL 16 |
| `TimelineEventMapperTest` | 13 | none (pure) |
| `CrmQueryScopeTest` | 11 | none (pure) |
| `InboxFilterDefinitionTest` | 10 | none (pure) |
| `TicketNumberTest` | 8 | none (pure) |
| `CrmDomainExceptionTest` | 8 | none (pure) |
| `TimestampsTest` | 7 | none (pure) |
| `CaseSolutionMatchingTest` | 6 | none (pure) |
| `CursorPageTest` | 6 | none (pure) |

Module-scoped, for iteration:

```
.\gradlew.bat :backend:crm-core:test --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat :backend:inbox-helpdesk:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```

> `--rerun-tasks --no-build-cache` is required. A plain `test` restores the Gradle cache and
> reports up-to-date without executing anything.

### The acceptance journey

`InboxJourneyIntegrationTest > customer message -> AI/human -> escalation -> employee response -> resolution`
walks it end to end against real PostgreSQL and asserts each step:

1. **Customer message** — `InboundMessageService.receive` resolves the handle to a customer with a
   CX id and a channel identity, opens a thread in `HUMAN` mode, and stores the message. The
   provider's message id is the inbound idempotency key.
2. **AI/human transition** — the thread is returned to AI and taken back by an escalation. The
   write is conditional on both the expected mode and `modeVersion`; the takeover moves the thread
   to `PENDING` and writes a `TAKEN_OVER` entry.
3. **Escalation handoff** — assignment to an employee who belongs to the project; the workload is
   recounted, and an `ASSIGNED` entry is written.
4. **Employee response** — the row is persisted before any delivery is attempted and starts
   `PENDING`; `firstResponseAt` is captured; the audit stream records `EMPLOYEE_REPLIED`.
5. **Resolution** — a resolution is recorded, the thread is closed and released, the workload is
   recounted back to zero, and five audit actions are present.

### The legacy fixture comparison

`src/test/resources/fixtures/legacy/*.json`, compared by `LegacyFixturesComparisonTest`:

| Fixture | Legacy source | What it pins |
| --- | --- | --- |
| `conversation-thread.json` | `conversations.service.ts` → `getById` + `toSummary` | The full thread: summary, two messages, four timeline entries in `occurredAt` order. |
| `conversation-summary.json` | `conversations.service.ts` → `inbox` + `toSummary` | An unassigned inbox row. |
| `customer-detail-empty.json` | `customers.service.ts` → `getById` | The empty 360 view, including the `leads` key and the absence of `organizationId`/`createdAt`. |
| `customer-list.json` | `customers.service.ts` → `list` | The list row, including `followUps`. |
| `note-list.json` | `notes.service.ts` → `listForResource` | The nested `{id, fullName}` author object. |

How the comparison works, and what it does **not** claim:

- The legacy response builders are pure functions of the row, so a fixture pins the **projection**.
  The container suites pin the **row**. Each covers what the other cannot.
- The comparison asserts the **exact key set** at every level (order-insensitive: a `jsonb` column
  does not preserve member order, and a missing or extra key is the real contract), and asserts
  **literal values** — the ticket number, the rendered timestamp, the payload keys, the delivery
  status, the author object.
- Ids and timestamps that are genuinely generated are listed in the fixture's `volatile` array and
  replaced by a marker.
- The thread fixture seeds fixed rows rather than going through the inbound pipeline, because a
  ticket number embeds its creation date and would otherwise pin the fixture to the day the suite
  runs. `TicketNumberTest` pins the format separately.
- A hygiene test asserts every fixture in the directory is claimed by the suite and names its
  legacy source file, so a new fixture cannot be written and forgotten.

## Security / tenancy checks

- **Every statement is scoped before it exists.** `CrmQueryScope` has no public constructor; only
  `TenantQueryGuard` can produce one. A client project id is validated by the guard before a
  predicate is built, and the tenant parameters are written last so a caller cannot shadow them.
- **No membership ⇒ no data** (FR-1.4). A principal with no projects gets `and 1 = 0`, asserted
  over the customer list, the conversation list, the notes list and the queue. This was a real bug:
  the first implementation emitted `in ()`, a PostgreSQL parse error.
- **Cross-tenant and cross-project negatives** are asserted against real rows: a customer, a
  conversation, a note, a classification question, a resolution, a ticket read and a ticket write.
- **Non-disclosure** (FR-2.7). An out-of-scope id and a nonexistent id produce the same 404, the
  same code and the same message; asserted at the service layer and over HTTP by comparing the two
  error bodies.
- **A requested project outside the scope is refused**, never widened.
- **A claimed tenant that is not the caller's is refused** in identity resolution.
- **Isolation is symmetric**: tenant B's admin cannot see tenant A's thread.
- **Search cannot read another tenant's message text**: the `exists` branch is correlated to the
  conversation's own `organizationId`/`projectId` columns.
- **LIKE wildcards are escaped**, so a customer name containing `%` cannot turn a search into
  "match everything".
- **The timeline carries no customer text** — a length, never a body.
- **Audit rows carry field names, never values**: asserted with a value that must not appear.
- **The customer-facing transcript excludes** timeline events, employee names, delivery failures
  and `SYSTEM` messages — enforced in the query, not by a response filter.
- **Method security is enforced on the services**, and the HTTP tests hit the real Spring Security
  chain: mutations without a CSRF token are refused.
- **Assignment requires organization + project membership**, not just an id.
- **The dev inbound simulator** 404s unless the flag is set, and the configuration refuses to start
  with the flag on in production.
- **No secret is read or printed.** `MockChannelProbe` has no statement that selects
  `credentials` or `webhookSecret`.

## Migration / data impact

- **Nothing was migrated.** No Flyway migration was added or applied; `backend/database/**` and
  `ops/**` are untouched.
- The development database was re-checked after the run: still **45** application tables, still
  **0** `flyway_schema_history` rows, still only `firstelement` and `postgres` databases.
- All test data lives in disposable Testcontainers `pgvector/pgvector:pg16` instances, reclaimed
  by Ryuk.
- No `db push`, no `prisma migrate`, no DDL against any persistent database.
- No data shape was altered: every column is read and written with its verified quoted camelCase
  name and `LocalDateTime`, so legacy rows are readable as they are.

## Known risks / debt carried forward

1. **No integer `version` column.** `updatedAt` is a correct but coarse token: two writes in the
   same millisecond are indistinguishable, and the timestamp is visible to clients that read the
   row. A dedicated integer on `customers`, `conversations` and `notes` would be cleaner. Needs a
   database-agent migration.
2. **The knowledge-search half of classification is absent by design.**
   `CaseSolutionRetriever` implements the deterministic `case_solution_mappings` path exactly; the
   pgvector fallback is Phase 10. When it returns `null`, `suggestion` is `null` — which the
   contract already allows. A second implementation is a Phase 10 change in
   `:backend:knowledge-ai`; the seam is already there.
3. **New threads start in `HUMAN`.** `InboundMessageService.hasActiveAgent` returns `false` until
   Phase 10 supplies the `ai_agents` lookup. The call site does not change.
4. **Idempotency is only on replies.** There is no `idempotency_records` table in the live schema
   and no migration was permitted. `POST /notes` is not idempotency-keyed; the case saves are
   naturally idempotent through their unique indexes, and close/reopen are idempotent by target
   state. A general store needs the same migration as item 1.
5. **The unassigned queue is a read model, not a router.** Availability, skills, working hours,
   capacity and department are deliberately not consulted; those are Phase 6.
6. **`MockChannelProbe` is a temporary seam.** Channels are Phase 5's ownership; this is one scoped
   `exists` read that the dev simulator needs, and it reads no secret column. Phase 5's connector
   registry should replace it.
7. **The `Lead` and `appointments` sub-projections are read-only.** The customer response shape
   requires them, but their writes belong to the CRM-middle and clinic modules.
8. **`ConversationEventType.TAG_ADDED` and `NOTE_ADDED` are mapped but not emitted.** The legacy
   runtime never emitted them either, and emitting them now would change the timeline a client
   renders. The mapper supports them for Phase 5/6 producers.
9. **No OpenAPI contract test** pins these endpoints' shapes; `LegacyFixtureComparisonTest` pins
   the JSON bodies, which is most of the same ground, but not the generated schema.
10. **`InboxHttpIntegrationTest` binds the principal itself** rather than exercising the session
    filter chain, because MockMvc does not run it. The security *chain* is still exercised (CSRF,
    authentication) but cookie authentication is Phase 3's already-proven path.

## Bugs found by the tests in this phase

Recorded because each one is the kind of defect only a real database finds:

| Bug | Found by | Fix |
| --- | --- | --- |
| `in ()` for a no-membership scope → PostgreSQL parse error → 500 | `CrmCoreIntegrationTest` | `and 1 = 0`, with a unit test that pins the SQL. |
| `table name "e" specified more than once` — the inbox join was appended twice | `InboxJourneyIntegrationTest` | The base projection already carries `employees e` and `customers cu`. |
| `List.of(...)` in the assignment recount threw on the common unassign case | `InboxJourneyIntegrationTest` | `Arrays.asList`, which tolerates the null that both sides legitimately can be. |
| `Map.copyOf` / `Map.of` rejected the nulls an unassignment payload carries | `TimelineEventMapperTest` | Null-tolerant `LinkedHashMap`. |
| `CursorPage.hasMore()/isEmpty()` serialized as `more`/`empty` on every paged response | `LegacyFixtureComparisonTest` | Renamed to `hasNextPage()`/`hasNoItems()`, with a test asserting no bean getters exist. |
| The 360 view's `leads` key and the list's `followUps` key were collapsed | `LegacyFixtureComparisonTest` | Corrected to the two distinct legacy names. |
| A grouped aggregate with no `group by` | `InboxJourneyIntegrationTest` | A dedicated grouped-count statement, tenant predicate from the scope. |
| Testcontainers 1.21.3 in one module and 1.21.4 in another; 1.21.3's docker-java cannot negotiate with this engine | The container suite | `extra["testcontainers.version"] = "1.21.4"` in the inbox module. |

## Follow-up for orchestrator

1. **Approve a migration adding an integer `version` to `customers`, `conversations` and `notes`**
   (database agent). It replaces `updatedAt` as the concurrency token and is the prerequisite for
   a general `idempotency_records` table. Both are additive and need no backfill.
2. **Phase 5 should own `MockChannelProbe`**, or confirm that the dev simulator keeps its own
   minimal `channel_connections` read.
3. **Phase 10 replaces `CaseSolutionRetriever`** with an implementation that also performs the
   pgvector fallback, and supplies `hasActiveAgent` so new threads can start in `AI` mode.
4. **Phase 6 reads `UnassignedQueueRepository.UNASSIGNED_ACTIVE`** as the single definition of
   "unassigned". It is the value the counts, the queue and the routing eligibility check must all
   use; a second definition is how a badge and a list drift apart.
5. **Decide the `views` surface.** Saved views (`/objects/{objectType}/views`) were in the legacy
   CRM middle workspace and are **not** migrated in this phase; `apps/api/src/views` remains the
   only implementation. Confirm whether they belong to the CRM-middle owner or to this one.
6. **Recommend a quality-agent rule**: every new module pins
   `extra["testcontainers.version"] = "1.21.4"`. The mismatch is invisible until a container suite
   fails for a reason that has nothing to do with the module.
7. **Recommend the CI gate** `./gradlew build --rerun-tasks --no-build-cache --no-daemon` plus a
   Docker availability check, so a cached result is never read as a pass (carried from Phase 3).