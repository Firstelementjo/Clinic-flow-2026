# Phase 18 — A refusal is a refusal, and a fault is still a fault

## Status

Implemented and tested. Awaiting contract promotion and manifest registration by the orchestrator.
**No OpenAPI change.** See [Contract impact](#contract-impact).

## The defect

`app-bootstrap`'s `ErrorHandlingAdvice` handled exactly five exception types:

```
MethodArgumentNotValidException, NoResourceFoundException,
HttpRequestMethodNotSupportedException, IllegalArgumentException, Exception   <- catch-all
```

`com.firstelement.crm.error.ValidationFailedException` was not among them. It extends
`CrmDomainException` extends `RuntimeException`, so it fell through to
`@ExceptionHandler(Exception.class)` and was answered **`500 INTERNAL_ERROR`**.
`MissingServletRequestParameterException` was unmapped for the same reason and had the same
outcome.

This was reported twice and recorded as Hook 17 and Hook 18 in
[`phase-11.md`](phase-11.md) §"Hook 17"/"Hook 18", reproduced against the running API:

```
GET /api/v1/conversations?projectId=<p>&filter=bogus
→ 500 {"code":"INTERNAL_ERROR","message":"Unexpected server error"}
  server: ValidationFailedException: filter must be one of
          [unassigned, all-open, assigned-to-others, assigned-to-me, history]

MissingServletRequestParameterException: Required request parameter 'projectId'
for method parameter type UUID is not present                        → 500
```

## Two things the status code was doing that a refusal should not do

**1. It manufactured incident signal.** A 500 is logged at `error`, alerted on, and counted as
an availability incident. Every ordinary validation rejection was therefore a false page. A
system that cries wolf about refusals stops being believed about real 500s — the cost is paid
on the *next* outage, not this one. This is now asserted, not just asserted-in-prose: a log
appender on the floor's logger proves a 400 emits no `ERROR` event and a 500 still does
(`a refusal is not logged as an incident; a fault is`).

**2. It was the client's only signal, and it was the wrong one.** `tests/contracts/openapi.json`
declares **142 operations and not one 4xx or 5xx response**. With no documented refusal
vocabulary, the status code is the entire contract a generated client has. `500` says "we are
broken"; it cannot say "you sent something wrong", so a client cannot branch on it.

## Why the handler was absent, which was not the inbox filter

The inbox filter was one caller out of several. The real cause is that
`CrmExceptionAdvice` declared

```java
@RestControllerAdvice(basePackages = {"com.firstelement.crm", "com.firstelement.inbox"})
```

`ValidationFailedException` is the CRM domain's **shared** refusal type. It is thrown by
`CaseService`, `NoteService`, `CustomerService`, `RequestParams` — and also by
`:backend:workflows-sla-routing`'s `WorkflowDefinitionService`, `WorkflowRunService`,
`RoutingService`, `EscalationService`, `SlaClockService`, `ScoringService`,
`HumanTakeoverService` and `AiSendService`. A package-scoped advice answers only for controllers
inside its own packages, so the moment one of those services was reached from a controller in
`com.firstelement.admin`, `com.firstelement.finance`, `com.firstelement.exports` or
`com.firstelement.operations`, the refusal became a 500. The scope *was* the bug; the filter was
a symptom.

The same class of trap exists one layer down and is reported, not fixed, in
[Other modules: same defect, other owner](#other-modules-same-defect-other-owner):
`SecurityExceptionAdvice` declares `basePackages = "com.firstelement"`, so a denial raised from a
controller outside that namespace answers 500. It is latent today because every production
controller is under `com.firstelement`, but the probe suite found it the honest way — see
[How the suite is built](#how-the-suite-is-built).

## What changed

### 1. `CrmExceptionAdvice` owns the CRM family, application-wide

`backend/crm-core/.../error/CrmExceptionAdvice.java`

- `basePackages` removed. One advice, one handler per exception type, every controller.
- `@Order(CrmExceptionAdvice.ORDER)` where `ORDER = Ordered.LOWEST_PRECEDENCE - 100`.
- `MethodArgumentTypeMismatchException` stays here and is now global, so an unbindable query
  parameter on a finance or export route is a 400 rather than a 500.

### 2. `ErrorHandlingAdvice` is the floor, and its position is declared

`backend/app-bootstrap/.../web/ErrorHandlingAdvice.java`

- `@Order(Ordered.LOWEST_PRECEDENCE)`, stated rather than discovered — see
  [The ordering](#the-ordering-was-decided-by-component-scan-order).
- New handlers, all refusals: `MissingServletRequestParameterException` → **400**,
  `MissingRequestHeaderException` → **400**, `HttpMessageNotReadableException` → **400**,
  `HttpMediaTypeNotSupportedException` → **415**, `HttpMediaTypeNotAcceptableException` → **406**.
- `@ExceptionHandler(Exception.class)` unchanged, and still the only thing that answers 500.

### 3. One CRM conflict, in the module that owns it

`CaseRepository` translated the unique-index collision on a classification question's `position`
into `PositionAlreadyUsedException` — a plain `RuntimeException` with no handler, so
`POST /api/v1/cases/projects/{projectId}/questions` answered 500 for a collision. `CaseService`
now raises `409 POSITION_ALREADY_USED` naming the occupied position, which is what the caller
needs in order to move it. 409 rather than 400 because nothing about the request is malformed and
a retry changes nothing; 409 rather than 404 because the question being inserted is provably new,
so the only possible failure is the occupied position. The update path still collapses both causes
into 404 — it genuinely cannot tell them apart.

## The enumeration

Every exception type that can reach a handler method was classified. **Handled** means a handler
exists; **floor** means it deliberately reaches the `Exception` catch-all and answers 500.

### Refusals — 4xx, handled

| exception | status | code | handler | was |
|---|---|---|---|---|
| `ValidationFailedException` | 400 | `VALIDATION_FAILED` (or the caller's own) | `CrmExceptionAdvice` | **500** |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` | `ErrorHandlingAdvice` | 400 |
| `MethodArgumentTypeMismatchException` | 400 | `VALIDATION_FAILED` | `CrmExceptionAdvice` | 500 outside crm/inbox |
| `MissingServletRequestParameterException` | 400 | `VALIDATION_FAILED` | `ErrorHandlingAdvice` | **500** |
| `MissingRequestHeaderException` | 400 | `VALIDATION_FAILED` | `ErrorHandlingAdvice` | **500** |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST_BODY` | `ErrorHandlingAdvice` | **500** |
| `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` | `ErrorHandlingAdvice` | **500** |
| `HttpMediaTypeNotAcceptableException` | 406 | `NOT_ACCEPTABLE` | `ErrorHandlingAdvice` | **500** |
| `IllegalArgumentException` | 400 | `BAD_REQUEST` | `ErrorHandlingAdvice` | 400 |
| `FilterNotPushableException` and the export catalogue/job family | 409/410/422/403 | module codes | `ExportExceptionHandler` | correct |

### Missing, denied, or wrong method — handled by the module that owns it

| exception | status | handler | was |
|---|---|---|---|
| `ResourceNotFoundException` | 404 | `CrmExceptionAdvice` | 500 outside crm/inbox |
| `VersionConflictException` | 409 | `CrmExceptionAdvice` | 500 outside crm/inbox |
| `NoResourceFoundException` | 404 | `ErrorHandlingAdvice` | 404 |
| `HttpRequestMethodNotSupportedException` | 405 | `ErrorHandlingAdvice` | 405 |
| `PolicyDeniedException` | **404, non-disclosing** | `SecurityExceptionAdvice` | 404 |
| `AccessDeniedException` | 403 | `SecurityExceptionAdvice` | 403 |
| `AuthenticationException` | 401 | `SecurityExceptionAdvice` | 401 |
| `AuthenticationFailedException` | 401 | `SecurityExceptionAdvice` | 401 |
| `RateLimitedAuthenticationException` | 429 + `Retry-After` | `SecurityExceptionAdvice` | 429 |
| `CsrfException` | 403 | `SecurityExceptionAdvice` | 403 |

### Genuine internal faults — deliberately left on the floor

These are faults in this codebase, not refusals of the caller's request. Mapping them to 4xx
would hide them.

| exception | why it is a fault |
|---|---|
| `IllegalStateException` | 120 throw sites; an invariant this server broke. `ExportExceptionHandler` reclassifies its own as 409 for the export controller, which is that module's decision to make. |
| `UnsupportedOperationException` | a capability the platform does not implement |
| `NoSuchElementException` | an `Optional` consumed without a check |
| `MissingPathVariableException` | a route declares a path variable it does not bind — a code defect |
| `ConversionNotSupportedException` | a declared property that cannot be converted — a code defect |
| `org.springframework.dao.DataAccessException` (outside `ExportController`) | a broken statement or constraint. `DuplicateKeyException` is 409 only in the export module, where it is raised by the module's own repository; everywhere else the CRM resolves duplicates explicitly for inbound idempotency and identity uniqueness. |
| `PermanentFailureException`, `ProviderSendFailedException`, `CredentialUnavailableException`, `UnsupportedCapabilityException` | connector and worker paths; no request thread reaches them |
| `AiRefusedException`, `AiRedactionFailedException` | worker paths; `ai-rag` publishes no controller |
| `UnknownQueueException` | extends `IllegalArgumentException`, so it already answers 400 — **wrong**, and reported below |

### Reachable from a controller but genuinely internal

`ConnectorRegistry.CredentialUnavailableException` is caught at both HTTP call sites
(`ChannelWebhookController.verifyTokenOf`, `InboundWebhookIntakeService`), and
`UnsupportedCapabilityException` only from a connector send. Left as-is; noted so the 500 that a
misconfigured real connector produces is a deliberate 500 and not a surprise.

## The ordering was decided by component-scan order

The part that was quietly load-bearing:

`ErrorHandlingAdvice` holds `@ExceptionHandler(Exception.class)`. Spring MVC walks the advice
beans **in order** and stops at the first one with a handler for the thrown type. Both
`ErrorHandlingAdvice` and `CrmExceptionAdvice` declared **no `@Order`**, so both defaulted to
`LOWEST_PRECEDENCE`, and the winner was whichever component scan happened to register first.

On the assembled application that was CRM first — but only because `:backend:crm-core` is listed
before `:backend:app-bootstrap` in the API module's dependency block. **Reordering one line of
`build.gradle.kts` would have silently turned every CRM 400, 404 and 409 into a 500**, and the
CRM suites would have gone red for a reason unrelated to what they test. A handler that is
registered but shadowed reads exactly like one that is absent, which is why the order is now
declared and asserted:

- `SecurityExceptionAdvice` — `HIGHEST_PRECEDENCE` (already; not this module's to change)
- `CrmExceptionAdvice` — `LOWEST_PRECEDENCE - 100`
- `ExportExceptionHandler` — `LOWEST_PRECEDENCE` (module-owned; see follow-ups)
- `ErrorHandlingAdvice` — `LOWEST_PRECEDENCE` (the floor; holds the `Exception` handler)

`a refusal handler was not widened into a catch-all` and
`the domain advice is declared ahead of the catch-all` assert both halves: the specific advice
wins behaviourally, and the declared orders agree. `the catch-all still answers 500` is asserted
separately, and it is the half that must never regress.

## The catch-all is still a catch-all

`a refusal handler was not widened into a catch-all` asserts `500 / 400 / 404 / 409` for the
fault route and the three domain routes in one place, and `the 500 body does not carry the
exception's message` asserts that a relation name and a filesystem path in the exception's
message do not reach the response. A catch-all that answered 400 would convert a real outage into
a silent bad request: nothing would page and the operator would believe the server was healthy.
That is strictly worse than the defect this phase fixed.

## How the suite is built

`ErrorMappingHttpIntegrationTest` (32 tests, `backend/app-bootstrap`) makes every assertion over
HTTP on a real route, because the defect was never in a handler — it was in **which handler the
dispatcher picked**. Calling `advice.validation(ex, request)` would have asserted the handler and
skipped the only part that was broken.

Routes live on `ErrorMappingProbeRoutes` (`com.firstelement.errorprobe`), one per exception,
with a `ProbeRefusalService` → `ProbeRefusalRepository` pair underneath so the nested cases are
genuinely raised three frames down.

Three construction decisions, each forced by something measured:

- **`@Hidden`, and the class is not named `*Controller`.** `ApiApplication` component-scans
  `com.firstelement`, so a `@Controller` in this module's test sources joins *every* context here.
  Two gates count routes and neither should count a fixture. springdoc publishes what it finds in a
  running context — `@Hidden` settles that, and the exported document was diffed against the
  committed one to prove it. `check-openapi-contract.ps1` extracts every `*Controller.java` under
  `backend`, test sources included (deliberately, so a test-only controller cannot hide a product
  route from review). The first version of this probe **failed that gate** with seventeen
  `ROUTE ADDED without a contract change` findings. The honest options were to add seventeen
  fixture routes to `tests/contracts/openapi-paths.json` — which would put routes that exist in no
  deployment into the contract the Angular client is generated from — or to name the fixture for
  what it is. It is named `ErrorMappingProbeRoutes`, and the gate was re-run to prove the outcome
  rather than assumed.
- **The package is `com.firstelement`, deliberately.** The first version lived outside it and
  every cross-tenant negative came back `500 INTERNAL_ERROR` while the CRM ones were correct —
  because `SecurityExceptionAdvice` is scoped to `com.firstelement`. Keeping the probe outside
  would have made the security negative pass in a configuration the product never runs.
- **`RecordingSecurityEventPublisher` is `@Primary` and lives outside `com.firstelement`.** The
  production publisher needs a database. Replacing it lets the cross-tenant negative assert that
  the denial **still publishes a security event** — the half a status code cannot show. It is a
  `@Configuration` outside the scanned namespace so it exists in exactly one context and cannot
  silently replace the real publisher for the module's other suites.

`addFilters = false` takes the security chain out of the path — the probe routes sit outside the
API's permission model and this suite is about error mapping — and `CorrelationIdFilter` is added
back explicitly, because `requestId` is part of the error contract and a suite asserting bodies
without one would assert a shape no client receives.

It is deliberately **one flat suite, not `@Nested` classes**: a nested class is reported under its
`@DisplayName`, so seven groups would be seven manifest entries to register and keep in step.

## Security negatives — the ones that matter most

| assertion | what it protects |
|---|---|
| `a cross-tenant read is 404, not a 400` | a policy denial does not become a client error |
| `the denial and a nonexistent row differ in nothing a caller can see` | FR-2.7; byte-identical modulo `requestId` and `timestamp` |
| `a denial still publishes a security event` | a denial keeps **looking** like a security event, which a 400 would have stopped |
| `the denial's security event carries the correlation id the caller was given` | an operator can join a denial to the caller's report |
| `a validation refusal publishes no security event` | the security stream is not filled with ordinary client mistakes |
| `a capability denial with no object to leak is still 403` | the 404/403 distinction FR-2.7 depends on is intact |

`PolicyDeniedException` extends `AccessDeniedException`, not `CrmDomainException`, and the new
handlers are all narrower than `AccessDeniedException` — so no handler added in this phase *can*
reach a denial. The tests assert the rendered status anyway, because the type hierarchy is not
the same claim as the rendered answer.

## Other modules: same defect, other owner

Reported, **not** fixed. Each is outside this phase's ownership globs.

| module | exception | correct status | current | why it matters |
|---|---|---|---|---|
| `reporting-finance` | `FinancePeriodClosedException` | **409** | 500 | A late usage arrival into a locked period. Its message **embeds `organizationId` and `periodId`**, so if it ever reached HTTP through a generic handler it would also disclose. Not reachable from a controller today (the inbox writes no usage events; the resolver runs on the worker/ledger path), so it is latent — but it is a refusal, and `reporting-finance` should give it a handler rather than leave the catch-all to decide. |
| `worker-runtime` | `UnknownQueueException extends IllegalArgumentException` | 500 | would be 400 | The inverse defect. `QueueName.parse` raises it and `RetryClassifier` already special-cases it, so the worker path is correct today; the risk is that any future HTTP route reaching it inherits the application's `IllegalArgumentException -> 400` rule and reports a server bug as a client error. Not reachable from a controller today. Reported rather than changed because narrowing the `IllegalArgumentException` handler would turn real 400s into 500s across 299 call sites. |
| `tenant-security` | `SecurityExceptionAdvice` is scoped to `basePackages = "com.firstelement"` | — | 500 for any controller outside the namespace | Latent today; a denial from a future controller outside `com.firstelement` answers 500, which is the worst possible misclassification of it. Found by this suite's first iteration. |
| `reporting-exports` | `ExportExceptionHandler` declares no `@Order` | — | order-dependent | It ties with `ErrorHandlingAdvice` at `LOWEST_PRECEDENCE`, so the export module's 17 specific handlers beat the catch-all only because component scan happens to register `com.firstelement.exports.web` first. Declare an order. |
| `workflows-sla-routing` | `ActivationConflictException`, `UnnamedPrincipalException`, `LoopBudgetExceededException`, `NonRetryableFailureException` | 409 / 500 | 500 | Not reachable from a controller — the module publishes none. Correct as they are, recorded so a future controller does not inherit an unmapped family. |

## Contract impact

**The OpenAPI document does not change.** Measured, not argued:

```
committed  tests/contracts/openapi.json                128 paths / 142 operations
exported   backend/app-bootstrap/build/openapi/openapi.json   128 paths / 142 operations
PATHS IDENTICAL
OPERATIONS IDENTICAL
```

No 4xx or 5xx response was added to any operation. Adding a
`@ApiResponse(content = @Content(schema = @Schema(implementation = ErrorResponse.class)))` for
400/404/409/410/415/422/503 on 142 operations is an **integration-owner change** to
`tests/contracts/openapi.json`, and this phase does not make it. It should: the defect this phase
fixed was precisely that a client had no documented refusal vocabulary to branch on, and the
status codes are now accurate but still undocumented. The codes a client can branch on are
`VALIDATION_FAILED`, `BAD_REQUEST`, `MALFORMED_REQUEST_BODY`, `NOT_FOUND`, `VERSION_CONFLICT`,
`METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_ACCEPTABLE`, plus the module-owned families.

## Tests

New suite, for `tests/contracts/expected-suites.json`:

```json
{"suite":"com.firstelement.web.ErrorMappingHttpIntegrationTest","module":"app-bootstrap",
 "stage":"backend-unit","minTests":32,"kind":"unit","services":[],
 "note":"Phase 18: every error mapping asserted over HTTP on a real route -- the CRM refusal family is 400/404/409, the dispatcher refusals are 400/406/415, the floor is still 500 and logs at ERROR, a cross-tenant denial is still a non-disclosing 404 that publishes a security event, and a refusal raised three layers down keeps its status."}
```

Added to an existing suite, no manifest change needed: `CrmCoreIntegrationTest` (floor `33`,
stage `backend-integration`) gains
`a taken question position is a 409 conflict, not an unmapped 500`, taking it from 33 to 34.

No suite was renamed, deleted or emptied. No `@Nested` suite was introduced.

### Commands and results

```
gradlew.bat :backend:crm-core:test :backend:inbox-helpdesk:test :backend:identity-access:test
             :backend:tenant-security:test
gradlew.bat :backend:reporting-exports:test :backend:notifications-realtime:test
             :backend:channels:test :backend:administration:test :backend:reporting-finance:test
             :backend:operations-observability:test :backend:workflows-sla-routing:test
             :backend:ai-rag:test :backend:worker-runtime:test :backend:database:test
             :backend:shared-kernel:test
```

`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`,
`-Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"`.
PostgreSQL via Testcontainers only; the `firstelement` database was not read, written or stamped.

```
administration           suites=28   tests=109   failures=0 skipped=0
ai-rag                   suites=39   tests=223   failures=0 skipped=0
app-bootstrap            suites=3    tests=38    failures=0 skipped=0   <- 6 before, +32 new
channels                 suites=10   tests=81    failures=0 skipped=0
crm-core                 suites=10   tests=126   failures=0 skipped=0   <- +1 new
database                 suites=1    tests=23    failures=0 skipped=0
identity-access          suites=3    tests=59    failures=0 skipped=0
inbox-helpdesk           suites=5    tests=90    failures=0 skipped=0
notifications-realtime   suites=4    tests=35    failures=0 skipped=0
operations-observability suites=15   tests=57    failures=0 skipped=0
reporting-exports        suites=44   tests=200   failures=0 skipped=0
reporting-finance        suites=27   tests=129   failures=0 skipped=0
shared-kernel            suites=1    tests=3     failures=0 skipped=0
tenant-security          suites=9    tests=81    failures=0 skipped=0
worker-runtime           suites=11   tests=71    failures=0 skipped=0
workflows-sla-routing    suites=19   tests=231   failures=0 skipped=0
TOTAL suites=229 tests=1556 failures=0 skipped=0
```

Baseline was 1523 tests / 228 suites. Delta is exactly `+32` (the new suite) and `+1` (the
position conflict), `+1` suite. Zero skipped, so nothing was hidden.

```
powershell -NoProfile -ExecutionPolicy Bypass -File tests\contracts\check-error-contract.ps1 -RepoRoot .
  STAGE error-contract VERDICT: PASS -- every advice builds the shared ErrorResponse
  exception advices found: 4

powershell -NoProfile -ExecutionPolicy Bypass -File ops\quality\checks\check-openapi-contract.ps1 -RepoRoot .
  STAGE openapi contract: 142 committed route(s), 142 in code
  routes added   : 0
  routes removed : 0
  STAGE openapi-contract VERDICT: PASS -- the committed route inventory matches the controllers

powershell -NoProfile -ExecutionPolicy Bypass -File ops\quality\checks\check-architecture.ps1 -RepoRoot .
  STAGE architecture VERDICT: PASS
```

Four advices, one `ErrorResponse` shape each, no advice body interpolating a resource identity.
The single `FE-WEB-003` advisory finding is **pre-existing**: it was measured again with this
phase's changes stashed and reported identically.

## Files changed

| file | change |
|---|---|
| `backend/crm-core/src/main/java/com/firstelement/crm/error/CrmExceptionAdvice.java` | scope widened to the application, `@Order` declared |
| `backend/crm-core/src/main/java/com/firstelement/crm/service/CaseService.java` | a taken question position is `409 POSITION_ALREADY_USED` |
| `backend/app-bootstrap/src/main/java/com/firstelement/web/ErrorHandlingAdvice.java` | `@Order` declared; five dispatcher refusals mapped |
| `backend/crm-core/src/test/java/com/firstelement/crm/CrmCoreIntegrationTest.java` | +1 test |
| `backend/app-bootstrap/src/test/java/com/firstelement/web/ErrorMappingHttpIntegrationTest.java` | new, 32 tests |
| `backend/app-bootstrap/src/test/java/com/firstelement/errorprobe/ErrorMappingProbeRoutes.java` | new, test fixture |
| `backend/app-bootstrap/src/test/java/com/firstelement/errorprobe/ProbeRefusalService.java` | new, test fixture |
| `backend/app-bootstrap/src/test/java/com/firstelement/errorprobe/ProbeRefusalRepository.java` | new, test fixture |
| `backend/app-bootstrap/src/test/java/errorcontract/probe/ErrorMappingProbeConfiguration.java` | new, test fixture |

## Known risks

1. **`ExportExceptionHandler` still ties with the floor.** Not this phase's module. If a future
   dependency-order change registers `app-bootstrap` before `reporting-exports`, every export
   refusal becomes a 500. Declaring an `@Order` on that advice closes it.
2. **`IllegalArgumentException` remains a 400 catch-all.** Accurate for the request-shaped uses,
   wrong for the internal ones (see the follow-up table). Narrowing it is a project-wide decision
   about which of 299 call sites are caller mistakes; it is not a mapping change.
3. **A 406 response carries no body.** A caller demanding a representation the API cannot produce
   also refuses the JSON error body, so the status is the whole of the answer. That is inherent,
   and it is asserted rather than papered over.
4. **The probe is scanned into every context in `app-bootstrap`.** Harmless today (`@Hidden`, not
   named `*Controller`, and no other suite requests `/error-mapping-probe`). If a future suite in
   this module snapshots the handler table or the route inventory, the probe appears in it.