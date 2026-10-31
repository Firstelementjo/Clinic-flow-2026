# Phase 5b — worker runtime, channels/connectors and notifications/realtime

## Objective

Give the Spring runtime the three asynchronous spines Phase 4 deliberately left out, and
prove the guarantees the project requirements actually ask for rather than the ones that are
convenient to assert:

1. **A transactional outbox and a durable worker runtime** (`backend/worker-runtime`): an
   intent written in the *same transaction* as the domain change, claimed with
   `FOR UPDATE SKIP LOCKED` under a lease, retried with exponential backoff and jitter,
   dead-lettered at the attempt ceiling, reaped after a crash, and replayed only by an
   operator and only with an audit trail.
2. **The channel/connector boundary** (`backend/channels`): one provider-neutral port,
   Mock/WhatsApp/Instagram/Messenger/Telegram adapters with declared capabilities, raw-body
   Meta signature verification and constant-time Telegram secrets, provider-event
   deduplication, and persist-outbound-before-delivery with delivery-status tracking.
3. **Notifications and realtime** (`backend/notifications-realtime`): a row is the record
   and the push is an optimization, SSE (never WebSocket) with reconnect and
   resync-from-API, a Redis bridge for multi-instance fan-out, and realtime as a refresh
   hint that is never the source of truth.

Scope was `backend/worker-runtime/**`, `backend/channels/**`,
`backend/notifications-realtime/**` and this report. Nothing else was modified; see
"Follow-up" for three defects found in code this phase does **not** own.

## Delivered behaviour

### `backend/worker-runtime` — 38 production classes, 4 452 lines

| Concern | How it is delivered |
| --- | --- |
| Same-transaction outbox | `OutboxEnqueuer` requires an active transaction (`publishing outside a transaction is refused`) and writes through `OutboxRepository.ENQUEUE` with `on conflict ("organizationId","idempotencyKey") do nothing`. The claim statements are transcribed from `ops/flyway/reference/outbox-claim.sql`, not re-derived: the predicate, the `ORDER BY "nextAttemptAt","createdAt","id"`, the single-statement claim-and-update and the `(now() AT TIME ZONE 'UTC')` comparisons all exist so `outbox_events_claim_idx` stays the right index. |
| Tenant-scoped claim | `claimFor(organizationId, workerId, batchSize, leaseSeconds)` refuses a `null` organization, so the tenant-less sweep index cannot be reached by accident. A claimed row is re-read under tenant scope before the handler runs. |
| Lease + reaper | `LEASED`/`leaseOwner`/`leaseUntil`; §7 `REAP` returns expired leases to `PENDING` with `FOR UPDATE SKIP LOCKED`, is idempotent, and keeps the dead attempt counted. `LeaseReaper` is its own component because both the scheduler and the `outbox-maintenance` job call it. |
| Attempts | Every attempt is an append-only `job_attempts` row written **before** the terminal write, so a crash between them leaves a recorded failure and a still-leased row rather than a completion with no attempt. `recordAttempt` also feeds the metrics. |
| Retry classification | `RetryClassifier` separates permanent (own-input rejection, lost tenant scope, unsupported capability, undeclared queue, provider rejection) from transient (timeout, network, DB contention), treats an unrecognised failure as *retryable and counted* rather than swallowing it, and flattens/truncates any provider summary before it reaches a log or a column. |
| Backoff + jitter | `BackoffPolicy`: `base * 2^(n-2)` clamped to `maxBackoff`, symmetric jitter from an injected randomness source, and `bounds(policy, nextAttemptNumber)` so a test asserts the interval instead of a coin flip. |
| Dead letter | `failPermanently` writes **both** facts: the event's terminal `state='DEAD'` + reason and the operator's `job_dead_letters` row (one per job, accumulating `attempts`, `payloadSummary` built from counts and classes — never the payload). |
| Replay | `OutboxReplayService.replay` re-reads under the operator's tenant, refuses a machine principal (an unattributable replay is refused), is idempotent because the update only matches `DEAD`, records who/when on the event, marks the DLQ row `REPLAYED`, writes a `job_attempts` row with `isReplay=true` naming the failure, and publishes a `BUSINESS_ACTION` audit row — including for a no-op replay. |
| Queue classes + fairness | `QueueClass`/`QueuePolicyCatalog` give every declared queue a class and a budget; `TenantFairnessPolicy` rotates tenants so one appears once before any appears twice, caps in-flight per tenant and reserves slots per queue class, and a tenant at its limit is *deferred* (returned to `PENDING`, no attempt written) rather than failed. |
| Graceful drain | `DispatcherLoop` is a `SmartLifecycle` at `Integer.MAX_VALUE - 100`, so it stops before the datasource and Redis close; `stop()` sets the drain flag, awaits in-flight jobs for `drainSeconds`, and whatever is unfinished keeps its lease for the reaper. |
| Health on the management port | `WorkerHealthIndicator` + `QueueHealthService` on `/actuator/health`: `DOWN` only for leases awaiting reap or a stalled loop, `OUT_OF_SERVICE` while draining, `UP` otherwise, and process-level details only (worker id, registered queues, jobs in flight, batch/lease/fairness, expired leases). No tenant id and no payload. |
| Job progress | `JobProgressService` derives progress from the durable record rather than storing a counter beside it. |

### `backend/channels` — 34 production classes, 3 934 lines

| Concern | How it is delivered |
| --- | --- |
| The port | `MessagingConnector` + `NormalizedIncomingMessage`/`NormalizedAttachment`/`OutgoingMessage`/`SendResult`/`ConnectorCapability`, a faithful port of `packages/connectors`. `NormalizedIncomingMessage` refuses to be constructed without a provider id, and its `auditLabel()` is an id pair rather than its text. |
| Adapters and capabilities | `ConnectorCatalog` declares Mock/WhatsApp/Instagram/Messenger/Telegram explicitly — including that WhatsApp is the only channel with a 24-hour customer-service window, that Telegram has edits/deletes but no read receipts, and that Mock has no signature scheme. An unsupported capability is a `PermanentFailureException` carrying the channel and the capability. |
| Inbound authenticity | `RawBodyCapturingFilter` buffers the body for `POST /webhooks/**` **before** anything reads it (a controller cannot get the raw bytes from a `ContentCachingRequestWrapper`), bounds it at 1 MiB (413 rather than an allocation), and re-serves it byte-identically. `MetaSignatureVerifier` checks the `sha256=` prefix and length first, then compares with `MessageDigest.isEqual`; `TelegramSecretVerifier` compares the shared secret in constant time and reports `NOT_CONFIGURED` distinctly from a mismatch. A verification result never renders the secret or the body. |
| Provider-event dedup | `ProviderEventStore` reserves the provider event id in `idempotency_records` (scope `PROVIDER_EVENT`, tenant- and channel-scoped, length-bounded) in the same transaction that enqueues the intent, and completes it with the row it produced. The outbox `idempotencyKey` is the second layer; `messages_projectId_channel_externalMessageId_key` is the guarantee. |
| Persist before send | `OutboundDeliveryService` never creates a message: it reads a `PENDING` outbound row, refuses one that is already `SENT`/`DELIVERED`/`READ` (the idempotency proof), applies `OutboundGuard` (limit, capability, session window) *before* calling the provider, and writes `SENT` with the provider message id. Every refusal is permanent, with the reason on the message row. |
| Delivery status tracking | `OutboundDeliveryRepository` carries `deliveryStatus`, `deliveryAttempts`, `providerMessageId`, `lastError`, and a status callback that advances the status and never downgrades it. |
| The HTTP surface | `ChannelWebhookController` (Meta verify handshake, inbound webhooks, provider status callbacks) and `ChannelCapabilityController` (the Admin panel read model, tenant-scoped, never a secret). |

### `backend/notifications-realtime` — 12 production classes, 1 461 lines

| Concern | How it is delivered |
| --- | --- |
| Row first, push second | `NotificationService.create` is `@Transactional` and writes the row; `RealtimePublisher.publish` is contractually incapable of throwing, so a failed push cannot roll back a notification. `NotificationFanoutJobHandler` is the durable path: a queued push re-reads the row and converges after a broker outage. |
| SSE, not WebSocket | `GET /api/v1/realtime/stream` returns `text/event-stream` on the existing cookie session — no second authentication path. `RealtimeStreamService.open` sends the `resync` frame and then a heartbeat on **every** connection including a reconnect, filters topics server-side, and removes a subscriber on completion, timeout or error. |
| Resync from the API | The resync frame names `/api/v1/realtime/state` and carries no row counts; that endpoint returns the unread count, the user-scoped list and the realtime health read model. A dropped frame is therefore always repairable. |
| Frames carry ids | `RealtimeEvent.toFrame()` is type, version, tenant, project, user, resource, timestamp, payload — never a message body. The notification payload holds the title and type the list itself renders and no body. |
| Multi-instance fan-out | `RedisRealtimeBridge` publishes an ids-only envelope (`payload` always `{}`) on `firstelement:realtime` and `deliverRemote` delivers another instance's envelope to this instance's subscribers after re-deriving the topic with the same `RealtimeTopicPolicy` that authorizes subscriptions. The publisher's own echo is suppressed by a bounded (4 096-entry) access-ordered LRU of frame ids. An unusable envelope — oversized, tenant-less, unknown type, malformed id, not JSON — is ignored, never thrown. |

## Files

### Changed in this session (the previous agent's code is on disk and compiles; this is the delta)

| File | Change |
| --- | --- |
| `backend/notifications-realtime/src/test/java/.../RealtimeStreamHttpIntegrationTest.java` | Rewritten onto a `RANDOM_PORT` real server (see "Defects found"). No assertion removed. |
| `backend/notifications-realtime/src/main/java/.../realtime/RedisRealtimeBridge.java` | The bridge actually encodes, decodes, delivers and suppresses its echo. Previously the listener dropped every frame. |
| `backend/notifications-realtime/src/main/java/.../realtime/RealtimeStreamService.java` | `onMessage` delegates to the bridge instead of decoding to nothing; `subscribeToBridge` honours `firstelement.realtime.bridge-enabled`. |
| `backend/notifications-realtime/src/main/java/.../config/RealtimeConfiguration.java` | An `ApplicationRunner` subscribes to the bridge once the context is up. |
| `backend/notifications-realtime/src/main/java/.../worker/runtime/OutboxDispatcher.java` | Added `endDrain()`. |
| `backend/worker-runtime/src/main/java/.../runtime/DispatcherLoop.java` | `start()` clears the drain flag. |
| `backend/notifications-realtime/src/test/java/.../NotificationsFixture.java` | `reset()` also clears `sessions` and `audit_logs`. |
| `backend/notifications-realtime/src/test/java/.../NotificationsIntegrationTestBase.java` | Pins `stream-timeout-seconds` to its 30 s floor so a stalled stream fails instead of hanging. |
| `backend/worker-runtime/src/test/java/.../WorkerRuntimeHealthIntegrationTest.java` | Made the stranded-lease and drain tests deterministic; `@DirtiesContext(AFTER_CLASS)`. |
| `backend/notifications-realtime/src/test/java/.../RealtimeBridgeFanoutIntegrationTest.java` | **New.** 7 tests: the bridge fan-out contract. |
| `backend/notifications-realtime/src/test/java/.../realtime/RedisRealtimeBridgeEchoTest.java` | **New.** 3 tests: the publish side, including the broker echo. |

Not modified by me but part of the delivered phase: every other production and test
class in the three modules, plus the two orchestrator fixes below.

| Orchestrator fix (kept, not reverted) | File |
| --- | --- |
| `DeadLetterRepository.UPSERT` no longer counts a **death** as a replay; `MARK_REPLAYED` increments `replayCount`, still guarded on `status='OPEN'` | `backend/worker-runtime/src/main/java/.../history/DeadLetterRepository.java` |
| Two deaths give `attempts=5`, `replayCount=0`, `lastReplayedAt=null` | `backend/worker-runtime/src/test/java/.../OutboxDeadLetterReplayIntegrationTest.java` |

## Verification (exact commands, Windows, Docker Desktop 29.8.1, JDK 24.0.1)

The module gate for the blocking suite, re-executed from scratch:

```
.\gradlew.bat :backend:notifications-realtime:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```
→ `BUILD SUCCESSFUL in 1m 16s`. **34 tests, 0 failures, 0 errors, 0 skipped** across
4 classes.

The three modules together:

```
.\gradlew.bat :backend:worker-runtime:test :backend:channels:test :backend:notifications-realtime:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```
→ `BUILD SUCCESSFUL in 1m 31s`.

| Module | Suites | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| `:backend:worker-runtime` | 11 | **71** | 0 | 0 | 0 |
| `:backend:channels` | 10 | **81** | 0 | 0 | 0 |
| `:backend:notifications-realtime` | 4 | **34** | 0 | 0 | 0 |

Per-suite, so `tests/contracts/expected-suites.json` can be raised to these numbers:

| Suite | Reported name | Tests | Manifest floor now |
| --- | --- | --- | --- |
| `com.firstelement.worker.OutboxLifecycleIntegrationTest` | — | 15 | 15 |
| `com.firstelement.worker.OutboxDeadLetterReplayIntegrationTest` | — | 5 | 5 |
| `com.firstelement.worker.OutboxEnqueuerTransactionTest` | — | 7 | 7 |
| `com.firstelement.worker.OutboxDispatcherFairnessIntegrationTest` | — | 6 | 6 |
| `com.firstelement.worker.WorkerRuntimeHealthIntegrationTest` | — | 3 | 3 |
| `com.firstelement.worker.queue.BackoffPolicyTest` | — | 9 | 9 |
| `com.firstelement.worker.queue.RetryClassifierTest` | — | 2 | 2 |
| `com.firstelement.worker.queue.RetryClassifierTest$Permanent` | permanent failures are not retried | 6 | 6 |
| `com.firstelement.worker.queue.RetryClassifierTest$RateLimited` | rate limiting is retryable but bounded by the queue policy | 3 | 3 |
| `com.firstelement.worker.queue.RetryClassifierTest$Transient` | transient failures are retried | 6 | 6 |
| `com.firstelement.worker.runtime.TenantFairnessPolicyTest` | — | 9 | 9 |
| `com.firstelement.channels.WebhookVerificationTest` | — | 3 | 3 |
| `com.firstelement.channels.WebhookVerificationTest$Meta` | Meta: sha256 over the raw bytes | 9 | 9 |
| `com.firstelement.channels.WebhookVerificationTest$MetaHandshake` | Meta: the GET registration handshake | 2 | 2 |
| `com.firstelement.channels.WebhookVerificationTest$Telegram` | Telegram: a shared secret | 6 | 6 |
| `com.firstelement.channels.ConnectorCapabilitiesTest` | — | 16 | 16 |
| `com.firstelement.channels.CrossTenantNegativesIntegrationTest` | — | 9 | 9 |
| `com.firstelement.channels.OutboxSameTransactionIntegrationTest` | — | 11 | 11 |
| `com.firstelement.channels.ProviderEventDeduplicationIntegrationTest` | — | 9 | 9 |
| `com.firstelement.channels.OutboundDeliveryIntegrationTest` | — | 13 | 13 |
| `com.firstelement.channels.OutboundCrashRecoveryIntegrationTest` | — | 3 | 3 |
| `com.firstelement.notifications.NotificationDeliveryIntegrationTest` | — | 19 | 19 |
| `com.firstelement.notifications.RealtimeStreamHttpIntegrationTest` | — | 5 | **not registered** |
| `com.firstelement.notifications.RealtimeBridgeFanoutIntegrationTest` | — | 7 | **not registered** |
| `com.firstelement.notifications.realtime.RedisRealtimeBridgeEchoTest` | — | 3 | **not registered** |

Every floor that exists already equals the suite's own count, so raising them is a
registration, not a change. The three unregistered suites are the only manifest work
this phase leaves.

The full build was deliberately **not** run here — it is the orchestrator's gate:

```
.\gradlew.bat build --rerun-tasks --no-build-cache --no-daemon --console=plain
```

## Security / tenancy checks

**Tenant scope is structural, not a convention.**

- `OutboxRepository`: every terminal write carries `"organizationId"` **and**
  `"leaseOwner" = :workerId`; a worker that does not hold the lease changes 0 rows and
  the fixture asserts the row is untouched. `claimFor` refuses a `null` tenant.
- `OutboxReplayService`: the event is re-read under the operator's tenant before anything
  is written, and a replay of another tenant's id returns `NOT_FOUND` with the no-op
  audit row written against the **caller's** tenant — never the victim's — so the trail
  cannot confirm that another tenant's id exists.
- `TenantScopedExecutor`: every handler runs under a context built from the claimed row's
  own project, and a job with no tenant is refused before it starts.
- `CrossTenantNegativesIntegrationTest` (9 tests) is the module's tenant-boundary suite:
  an enabled-connection lookup is scoped, a disabled connection is not selectable, the
  same provider event id in two tenants is two reservations, the Admin connector panel
  returns only the caller's tenant and no secret, and no connector adapter ever renders a
  credential.
- Realtime: topics are computed from the resolved `TenantContext` by `RealtimeTopicPolicy`;
  a foreign topic is refused, recorded as `Realtime.SubscriptionDenied` in `audit_logs`
  with the topic in the metadata, and never delivered (asserted both by subscription set
  and by a direct `registry.deliver`).
- Notifications are user-scoped as well as tenant-scoped: another user in the same tenant
  neither reads nor marks one, and is told nothing about whether it exists.

**Inbound webhooks.** Raw-body capture before parsing, bounded at 1 MiB; `sha256=`
prefix and length checked before a constant-time `MessageDigest.isEqual`; Telegram's
shared secret compared in constant time with `NOT_CONFIGURED` reported distinctly; a
failed verification is recorded on the security stream and the body is never logged.

**No secret or customer content in any sink.** The bridge envelope is ids only
(`"payload":{}`); `payloadSummary` on a dead letter is built from counts, queue and error
class; the dispatcher's log lines carry ids, queues, states, retry classes and counts;
`RealtimeEvent.auditLabel()` and `NormalizedIncomingMessage.auditLabel()` are id pairs.
Nothing in this phase reads or emits `.env`, credentials, tokens or connection
secrets.

**Test-only authentication.** The realtime HTTP suite now issues real revocable
`sessions` rows through `BrowserSessionStore` and sends the `fe_session` cookie, and uses
`GET /api/v1/auth/csrf` for the mutation path. No `SecurityMockMvcRequestPostProcessors`
shortcut is left in the class, so the authentication and CSRF guarantees under test are
the ones the product ships.

## Migration / data impact

**None.** This phase adds no migration, changes no DDL and touches no data outside
throwaway Testcontainers containers. It depends entirely on the Phase-5a slice
(`outbox_events`, `job_attempts`, `job_dead_letters`, `idempotency_records`) that is
already in the packaged Flyway chain and is applied by every test in these modules
(`flyway.enabled` is deliberately not disabled here). The statements in
`OutboxRepository` are transcriptions of `ops/flyway/reference/outbox-claim.sql`; if that
reference file ever changes, this repository must change with it.

The only schema-adjacent statement is a read-model one: the bridge envelope adds a
`frameId` to an **internal Redis message**, not to any table.

## Defects found by the verification

1. **`RealtimeStreamHttpIntegrationTest` hung forever** (the blocker). It called
   `mockMvc.perform(asyncDispatch(opened))` on an open `SseEmitter`;
   `firstelement.realtime.stream-timeout-seconds` is 1800 in production and MockMvc has
   no container timer, so dispatch blocked until the suite was killed. Completing the
   emitter first does not unblock it (already established).
   **Fixed** by moving the class to `@SpringBootTest(webEnvironment = RANDOM_PORT)` and
   reading the stream with a real HTTP client under an explicit 20 s deadline, closing the
   response body in a `finally` (what a closed browser tab does). All four guarantees
   survive and the suite now terminates deterministically; a stream that stops producing
   frames **fails** the assertions instead of parking a thread. Nothing was weakened and
   no assertion was dropped:
   - `theFirstFrameIsAlwaysResync` — 200, `Content-Type: text/event-stream`, `event:resync`
     first with `/api/v1/realtime/state` and `"version":1`, then `event:heartbeat`, and the
     resync index strictly before the heartbeat index;
   - `theStreamIsNotPublic` — an anonymous `Accept: text/event-stream` request gets 401
     `AUTHENTICATION_REQUIRED` as JSON, not an SSE stream, and `/api/v1/realtime/state`
     is 401 too;
   - `aForeignTopicIsRefusedAndRecorded` — exactly one `Realtime.SubscriptionDenied`
     audit row for the refused topic, and a frame on that topic never reaches a subscriber;
   - `reconnectResyncsFromTheApi` — a fresh connection gets `event:resync`, and
     `/api/v1/realtime/state` answers with the notification written while nothing was
     connected.
2. **The Redis bridge was dead code.** `RealtimeStreamService.subscribeToBridge()` was
   never called from anywhere, `firstelement.realtime.bridge-enabled` was read by nothing,
   and `onMessage` deliberately decoded to nothing — so with two API instances a client on
   instance A missed every frame published on instance B, which is exactly the limitation
   ADR-0003 names. **Fixed**: the subscription is made from an `ApplicationRunner` gated on
   the property, and `deliverRemote` re-derives the topic with `RealtimeTopicPolicy` and
   delivers to local subscribers, ignoring unusable envelopes instead of throwing.
3. **`WorkerRuntimeHealthIntegrationTest.strandedLeaseMakesHealthDown` was a race.** The
   class runs the real scheduler with `reap-millis=300`, so the background reaper could
   return the stranded row between the health assertion and `dispatcher.reap()` — the call
   under test then found nothing. **Fixed** by stopping the loop for that test, asserting
   DOWN → reap → claimable-again deterministically, and restarting it afterwards.
4. **A stopped loop could never be restarted.** `beginDrain()` set a flag nothing ever
   cleared, so a `stop()`/`start()` cycle left a dispatcher that claimed nothing and
   reported `OUT_OF_SERVICE` for the rest of the process's life. **Fixed** with
   `OutboxDispatcher.endDrain()` called from `DispatcherLoop.start()`.
5. **The live-dispatcher test class leaked its dispatcher into other suites.** Every suite
   in the module shares one PostgreSQL container, so a still-running loop from
   `WorkerRuntimeHealthIntegrationTest` claimed rows another suite had just seeded and made
   that suite's claim assertions false. **Fixed** with
   `@DirtiesContext(classMode = AFTER_CLASS)` on that class.

## Known risks / debt carried forward

1. **`UnmappedRequestMatcher` treats an unsatisfiable `Accept` as an unmapped path.** A
   `GET /api/v1/realtime/stream` with `Accept: application/json` is classified as unmapped,
   permitted by the catch-all, and then answered `406` by content negotiation instead of
   `401`. It leaks nothing — no handler runs and no data is produced — and the browser's
   real `Accept: text/event-stream` correctly gets `401` — but the security boundary's
   default-deny has a content-negotiation-shaped hole in it. This is
   `backend/tenant-security`, Phase 3's owner: the matcher should test the path, not the
   `produces` condition. The realtime test now sends the `Accept` a browser sends and says
   why in a comment.
2. **A frame delivered through the bridge carries fewer fields than the same frame
   delivered locally**, because the envelope is ids-only by design (the broker never holds
   customer words). A client must treat a frame payload as optional and resync. This is
   documented in `RedisRealtimeBridge` and asserted, but it is a wire-parity asymmetry a
   frontend engineer must know about before a multi-instance deployment.
3. **Echo suppression is best-effort.** The 4 096-entry LRU means a frame older than 4 096
   publications is delivered twice if the broker echoes it late. A duplicated refresh hint
   costs one extra API call and changes nothing the client renders; this is cheaper than an
   unbounded id set.
4. **`multiInstanceCapable` answers "configured", not "connected".** It is true whenever a
   connection factory exists, which is also true with the broker down. The evidence an
   operator needs is the subscription log line. Consider a real ping in the health read
   model.
5. **The org-wide claim sweep (§3 of the reference) is deliberately not implemented.**
   `claimFor` refuses a null tenant instead of silently widening the scan, so
   `outbox_events_dispatch_sweep_idx` is currently unused and Phase 5a's risk 4 stands.
6. **Lease length is a configuration risk, not a code one.** `lease-seconds` (60) must
   exceed the slowest handler; a shorter lease is a guaranteed double-delivery, which the
   handler's idempotency absorbs but which shows up as an operator-visible attempt count.
7. **Retention of `outbox_events`, `job_attempts`, `job_dead_letters` and
   `idempotency_records` is still undecided** (Phase 5a risk 1). `ProviderEventStore`
   stamps `expiresAt` at 30 days so a purge is expressible, but nothing deletes anything.
8. **No live-broker integration test exists.** The bridge is proven on both sides of the
   wire without Redis because a test that needs a live broker cannot run hermetically in
   this repository. A single optional smoke test against a `redis:7` container would close
   the last gap in the multi-instance claim.
9. **The worker-runtime health suite is the only one that runs a live dispatcher**, so it
   is the only one sensitive to context-cache state; the `@DirtiesContext` above is what
   keeps it isolated. Any future suite that enables the loop needs the same treatment.
10. **`apps/**` and `packages/**` legacy code is untouched**, as required by the strangler
    plan. The legacy gateway's single in-memory subject is still the deployed behaviour of
    the old runtime until it is retired.

## Follow-up for the orchestrator

1. **Register the three missing suites in `tests/contracts/expected-suites.json`** (I did
   not touch `tests/**`):
   `com.firstelement.notifications.RealtimeStreamHttpIntegrationTest` (5),
   `com.firstelement.notifications.RealtimeBridgeFanoutIntegrationTest` (7),
   `com.firstelement.notifications.realtime.RedisRealtimeBridgeEchoTest` (3, a pure unit
   suite — `services: []`, kind `unit-in-container-module`, like
   `ConnectorCapabilitiesTest`). Every existing Phase-5b floor already equals its suite's
   own count, so nothing needs raising.
2. **Run the full gate**: `.\gradlew.bat build --rerun-tasks --no-build-cache --no-daemon
   --console=plain`. These three modules contribute **186** tests (71 + 81 + 34); the
   repository total must be read from your own run. The JUnit results currently on disk sum
   to 501 across all ten modules, but seven of those modules were not re-executed in this
   session and their numbers are from the last full build.
3. **Phase 3 (`backend/tenant-security`): fix `UnmappedRequestMatcher`** so a path whose
   `produces` condition fails is still *mapped* and therefore still requires
   authentication (risk 1 above). This is a security-boundary change and needs the owner's
   integration coordination, not a worker-runtime edit.
4. **ADR-0003 / the Angular client owner**: a bridged frame may omit display fields, so the
   client must resync rather than render a partial notification (risk 2).
5. **Decide the org-wide sweep** (Phase 5a risk 4): if per-tenant claiming is the only
   path, `outbox_events_dispatch_sweep_idx` becomes unused and should be dropped by a later
   additive migration.
6. **Own the retention decision** (risk 7) before any Phase-7 Admin surface shows a
   dead-letter count that nobody prunes.
7. **Optional, cheap, closes the last multi-instance gap**: a `redis:7` container smoke test
   that publishes on one connection and asserts a listener on another receives it (risk 8).