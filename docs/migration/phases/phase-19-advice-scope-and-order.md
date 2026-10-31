# Phase 19 - A denial is not an incident, and the advice ladder is declared

## Status

Implemented and tested. Awaiting manifest registration by the orchestrator.
**No OpenAPI change.** See [Contract impact](#contract-impact).

## The two defects

Both were found by Phase 18 making the advice order explicit. Neither is a handler bug.

### Defect 1 - `SecurityExceptionAdvice` was scoped to a package

```java
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.firstelement")   // <- the defect
public class SecurityExceptionAdvice {
```

A `@RestControllerAdvice` that declares `basePackages` is consulted **only** for a controller in
that package. So a `PolicyDeniedException` raised from a controller outside it was not rendered as
the non-disclosing `404` the boundary exists to produce: it fell past the security advice to
`app-bootstrap`'s `@ExceptionHandler(Exception.class)` and a cross-tenant denial came back
**`500 INTERNAL_ERROR`**.

That is wrong twice, and the second reason is the one that made it a security finding rather than a
tidiness one:

1. A `500` is logged at `error`, alerted on and counted as an availability incident. So the request
   that tells you somebody is walking another tenant's ids is the request that manufactures the most
   incident signal, and the signal an operator learns to ignore is the signal that would have told
   them about the attack.
2. A refusal that is only a refusal for controllers somebody remembered to place in a namespace is a
   security control held by a convention rather than by configuration. It is correct today and it
   fails the day a controller is added outside the convention, with no diff that looks wrong.

**How it was found:** honestly, not by argument. Phase 18's error-mapping probe fixture was first
written outside `com.firstelement`, and every cross-tenant negative came back `INTERNAL_ERROR` while
the CRM ones were correct. The Phase 18 author moved the fixture inside the namespace and documented
why. That was the right call for a test that was about error mapping, and it left the defect latent
rather than fixed. Every production controller is under `com.firstelement`, so this was a trap with
nothing standing on it - which is exactly the kind that is reported as "not applicable" and then
fires later.

### Defect 2 - the order was load-bearing and nothing enforced it

Phase 18 replaced an accident with three explicit `@Order` declarations. That was the fix, and it
introduced the fragility: the ordering is now the *only* thing making a specific refusal beat the
catch-all, and no gate stopped a fourth advice from tying with the floor and reintroducing exactly
the component-scan nondeterminism Phase 18 removed.

Before Phase 18 the three advices all tied at `LOWEST_PRECEDENCE` and the winner was decided by the
line order of `build.gradle.kts`. A handler that is registered but shadowed is worse than one that
is absent, because it reads as fixed - so the property that decides whether a cross-tenant denial is
a `404` or a `500` was, until now, an emergent property of a build file's ordering.

## Fix 1: the advice applies to every controller

`basePackages` is removed. The advice is now declared `@RestControllerAdvice` with no selectors.

Widening an advice is only safe if (a) no handler in it competes with another advice for the same
exception, and (b) nothing it claims changes meaning when it applies everywhere. Both were checked
against the assembled application rather than assumed. **Per handler:**

| Handler | Exception | Widening safe? | Why |
|---|---|---|---|
| `policyDenied` | `PolicyDeniedException` | **Yes, and required** | This is the reason for the change. Nothing else handles it: `CrmExceptionAdvice`'s own javadoc records that `PolicyDeniedException` extends `AccessDeniedException` and not `CrmDomainException`, so no handler there can reach it; `ExportExceptionHandler` explicitly refuses to handle it "so the non-disclosing answer [is not] replaced by a disclosing one". A denial is the one refusal that must keep looking like a security event. |
| `accessDenied` | `AccessDeniedException` | **Yes** | Thrown directly by services in at least five modules (`MembershipAdminService`, `PeriodLifecycleService`, `AdjustmentService`, `RateCardService`, `ExportJobService`), and `PolicyDeniedException` is its only subclass in the repository. Spring resolves the most specific handler within an advice, so the `404` for a scope denial and the `403` for a capability denial coexist without either shadowing the other. |
| `authentication` | `AuthenticationException` | **Yes** | No other advice claims it. Disjoint from the two login handlers below because `AuthenticationFailedException` and `RateLimitedAuthenticationException` extend `RuntimeException`, **not** `AuthenticationException` - so no handler in this class can mask another. |
| `invalidCredentials` | `AuthenticationFailedException` | **Yes** | Its type is only ever constructed in `LoginService.invalidCredentials()`, so it is a login-flow vocabulary *by construction site*, not by advice scope. Widening cannot change what it means; the case where a credential failure would arise outside the namespace is precisely the case where a `500` would be the wrong answer. |
| `rateLimited` | `RateLimitedAuthenticationException` | **Yes** | Same reasoning: constructed only in `LoginService`. A `429` with `Retry-After` is correct wherever the limiter refuses, and the alternative is a `500`. |
| `csrf` | `CsrfException` | **Yes** | No subclass in the repository, no other advice claims it. Worth being precise about reach: the *common* CSRF failure never reaches this handler at all - it propagates out of the filter chain and `SecurityErrorRenderer` writes it, because `ExceptionTranslationFilter` runs before the dispatcher. This handler exists for the case a service validates a token itself, which is a case that must be `403 CSRF_TOKEN_INVALID` and never a `500` wherever it is called from. |

**What widening deliberately did not swallow,** so that the change is provably inert outside the
security family: `IllegalArgumentException`, `IllegalStateException`, the data-access exceptions and
every domain refusal are still unclaimed here. They belong to the floor and to `CrmExceptionAdvice`,
and `ExportExceptionHandler`'s scoped handlers for them are unaffected.

## Fix 2: `ops/quality/checks/check-advice-order.ps1`

A source-level gate. Cheap, static, no Gradle, no Docker, no database - about 2.5 seconds over 464
Java sources.

```
powershell -NoProfile -ExecutionPolicy Bypass -File ops/quality/checks/check-advice-order.ps1
```

| Rule | Fails when |
|---|---|
| `FE-ADVICE-001` | an advice declares no `@Order` |
| `FE-ADVICE-002` | two advices declare the same order |
| `FE-ADVICE-003` | an advice other than the floor declares `LOWEST_PRECEDENCE` |
| `FE-ADVICE-004` | the ladder does not have exactly one floor, or the floor is not last |
| `FE-ADVICE-006` | an `@Order` expression cannot be resolved to an int |
| `FE-ADVICE-005` | *advisory*: an advice narrows its scope to a package, annotation or type |

Two decisions worth recording.

**It reads `@Order` expressions, not just their presence.** `@Order(CrmExceptionAdvice.ORDER)` and
`@Order(Ordered.LOWEST_PRECEDENCE - 100)` are both real in this tree, and a gate that only recognised
a literal int would report "unordered" for the one advice that took the trouble to name its
constant. The resolver handles integer literals, the two `Ordered` anchors, a `static final int` on
another class in scope, and `+`/`-` over those. Anything outside that subset is `FE-ADVICE-006`: an
unverifiable order is not an order.

**Comments are blanked before the scan.** This repository's advices document themselves in prose that
quotes the annotations they declare - `SecurityExceptionAdvice`'s javadoc names
`@RestControllerAdvice` and `basePackages` in the paragraph explaining why the scope was widened, and
`ErrorHandlingAdvice`'s names all three orders. A scanner that reads javadoc as code reports a phantom
advice and then a phantom duplicate order. A gate that reports the prose gets reported back by anyone
who read the file, and then gets ignored.

### Proving the gate goes red

`ops/quality/checks/selftest-advice-order.ps1` writes 11 synthetic source trees to a throwaway root
and asserts the exit code and a marker string for each. A non-zero exit is not proof of the right
failure - a parse error also exits 1 - so every case names a string from the checker's own output.

```
powershell -NoProfile -ExecutionPolicy Bypass -File ops/quality/checks/selftest-advice-order.ps1
```

```
ok   a healthy, fully ordered ladder passes                                expected exit 0, got 0
ok   a fourth advice with no @Order                                       expected exit 1, got 1
ok   two advices declaring the same order                                  expected exit 1, got 1
ok   a non-floor advice sitting at LOWEST_PRECEDENCE                       expected exit 1, got 1
ok   two advices holding @ExceptionHandler(Exception.class)                expected exit 1, got 1
ok   the catch-all declared ahead of a specific advice                     expected exit 1, got 1
ok   an @Order this script cannot resolve to an int                        expected exit 1, got 1
ok   javadoc quoting @RestControllerAdvice is not a declaration           expected exit 0, got 0
ok   a test fixture mirroring the floor is ignored by default              expected exit 0, got 0
ok   the same fixture is audited with -IncludeTestSources                  expected exit 0, got 0
ok   no advices at all                                                     expected exit 1, got 1

advice-order selftest: 11/11 expectations held -- VERDICT PASS
```

The first case is load-bearing in the other direction: a gate that is red on everything cannot
demonstrate that it detects anything. Three of these eleven failed on first run for reasons worth
recording, because each was a real weakness in the checker rather than a wrong expectation:

- Every case initially reported "0 advices" and passed for the wrong reason. The fixtures were
  written under `build/`, which the checker skips as Gradle output. Fixtures now go to a temp root.
- A non-zero exit with the wrong marker caught two message-wording mismatches that an exit-code-only
  assertion would have scored as passes.
- The `-IncludeTestSources` case initially failed because a fixture advice sat at
  `LOWEST_PRECEDENCE`, i.e. it tied with the floor. The gate was right and the fixture was wrong.

## The advice inventory

Every `@RestControllerAdvice` in `backend/*/src/main/java`, as reported by the gate:

| Order | Advice | Module | Catch-all? |
|---|---|---|---|
| `-2147483648` (`HIGHEST_PRECEDENCE`) | `SecurityExceptionAdvice` | `tenant-security` | no |
| `2147483547` (`LOWEST_PRECEDENCE - 100`) | `CrmExceptionAdvice` | `crm-core` | no |
| `2147483647` (`LOWEST_PRECEDENCE`) | `ErrorHandlingAdvice` | `app-bootstrap` | **yes** |
| **undeclared** | `ExportExceptionHandler` | `reporting-exports` | no |

### `ExportExceptionHandler` - reported, not fixed

`backend/reporting-exports/.../ExportExceptionHandler.java` declares
`@RestControllerAdvice(assignableTypes = ExportController.class)` with **no `@Order`**. It ties with
the floor at `LOWEST_PRECEDENCE`, and the winner between them is decided by component-scan
registration order. It is not this phase's module, so it is reported here rather than edited.

The gate currently reports it, and that is correct behaviour:

```
FE-ADVICE-001  ExportExceptionHandler declares no @Order.
FE-ADVICE-005  ExportExceptionHandler is narrowed to (assignableTypes = ExportController.class). [advisory]
FE-ADVICE-006  ExportExceptionHandler declares no order this script can resolve. [consequence of 001]
```

**Recommended fix** for the owning agent: add `@Order(Ordered.LOWEST_PRECEDENCE - 200)`. It must sort
ahead of `ErrorHandlingAdvice` (which holds the catch-all) and behind the security advice. Any value
in `(HIGHEST_PRECEDENCE, LOWEST_PRECEDENCE)` that is not `-100` or another advice's order satisfies
both. `-200` is the natural choice because the advice's handlers are all specific export exceptions,
not a general family.

**Until that lands, `check-advice-order.ps1` exits 1 on this repository.** That is the gate doing its
job, and it is a real finding rather than a false positive: the advice really is unordered and really
does tie with the floor. Wiring it into `gate-stages.json` before the export module declares an order
will red the `format-static` stage, so the two changes should land together.

Note that `FE-ADVICE-005` on the same class is advisory and correct as written. Narrowing to
`assignableTypes = ExportController.class` is legitimate for a module that owns one surface: the
exception types it handles are export-specific and are only ever raised from that controller. The
narrowing would be a trap in the *security* family, which is why the rule records rather than fails.

## Tests

`backend/tenant-security/src/test/java/com/firstelement/security/error/SecurityExceptionAdviceScopeHttpTest.java`
- 15 tests, all over real HTTP through a real `DispatcherServlet`.

**The probe controller is outside `com.firstelement`** (package `securityscope.probe`), which is the
whole point: in the product every controller is inside the namespace, so a package-scoped advice looks
correct there forever. One test asserts that property of the fixture directly, so it cannot stop
covering the defect by being moved back inside.

| Assertion | What it holds |
|---|---|
| a cross-tenant denial raised outside `com.firstelement` is `404`, not `500` | **the defect**, over HTTP |
| the denial and a genuinely nonexistent row differ in nothing a caller can see | FR-2.7 non-disclosure, byte-compared |
| the denial body names no tenant, no project and no reason | the ids stay in the security stream |
| a denial still publishes a security event | a `404` alone is not what makes it a security event |
| the denial's security event carries the correlation id | the record is joinable to the log |
| a capability denial with no object to leak is still `403` | scope `404` and capability `403` coexist |
| a validation refusal publishes **no** security event | widening must not classify bad requests as denials |
| a missing row publishes no security event | nobody was denied |
| an unexpected fault is still `500` | the floor still catches what nothing claims |
| the `500` body still omits the exception's message | no disclosure through the floor |
| the advice is not scoped to a package, type or annotation | the declaration, not only the behaviour |
| the advice still sorts ahead of the floor | order is the mechanism, asserted as such |
| a nested denial keeps its `404` | raised from a service three layers down |

The twin comparison sends **the same correlation id on both requests** and normalises only
`requestId` and `timestamp`. Two generated ids would differ for reasons unrelated to disclosure and
would fail the assertion on the plumbing rather than on the contract.

### Why this suite mirrors two advices

`:backend:tenant-security` is depended on by `:backend:crm-core` and `:backend:app-bootstrap`, so this
module's tests cannot depend on either - a test-scope edge back is the cycle `check-architecture.ps1`
FE-ARCH-004 forbids. `ProbeAdviceLadder` therefore reproduces the two advices below the security one
with their order and handling width intact, and **only `SecurityExceptionAdvice` is the production
class**. That is sufficient for this suite's claim, which is about scope and ordering rather than
rendering; the assembled-application proof of the rendered bodies is `app-bootstrap`'s
`ErrorMappingHttpIntegrationTest`, which still passes unmodified.

No database and no container. The security event publisher is replaced by a recorder, as
`app-bootstrap`'s probe configuration does, because the property under test is what the advice
*published*, not what the JDBC publisher would have written. `spring-boot-starter-jdbc` is a
production dependency of this module, so its auto-configuration is excluded explicitly - otherwise the
context tries to build a `HikariDataSource` with no URL and the suite cannot start.

## What did not change

- **No OpenAPI change.** No route, no operation, no status code, no error body. The bodies this advice
  produces are unchanged; what changed is which controllers reach them.
- **No change to any other advice.** `CrmExceptionAdvice` and `ErrorHandlingAdvice` are untouched, and
  their tests still pass.
- **No gate file was edited.** `ops/quality/checks/` is shared infrastructure and
  `check-advice-order.ps1` plus `selftest-advice-order.ps1` are both new files. `selftest.ps1` was
  *not* modified even though it is the natural place for these cases, because it belongs to the
  quality agent and probes exactly one checker.

## Contract impact

**None, and nothing should be promoted.** The contract document
(`tests/contracts/openapi.json`) records paths, operations and schemas. A `404` that was previously a
`500` on a route no production client can reach, from a controller outside the product, changes no
path, no operation and no schema. `check-openapi-contract.ps1` was run after the change: 142 committed
routes, 142 in code, zero added, `VERDICT: PASS`. `check-error-contract.ps1` also passes, and
`ErrorResponse`'s field list is unchanged.

**This document is not promoted.** It is a phase report, not a contract.

## Follow-ups for other owners

1. **`reporting-exports`**: add `@Order(Ordered.LOWEST_PRECEDENCE - 200)` to `ExportExceptionHandler`,
   or state a deliberate reason not to. Until then the new gate exits 1 on this repository.
2. **Orchestrator**: register `SecurityExceptionAdviceScopeHttpTest` in
   `tests/contracts/expected-suites.json` with a deliberate `minTests` floor of 15.
   `check-no-hidden-skips.ps1` currently reports `UNREGISTERED SUITE` for it - correctly; the manifest
   is owned outside this phase.
3. **Orchestrator / quality**: decide whether `check-advice-order.ps1` joins `gate-stages.json`. It
   belongs in `format-static`, next to `check-scripts.ps1` and `selftest.ps1`, since it is the same
   class of gate: cheap, static, and a build input.
