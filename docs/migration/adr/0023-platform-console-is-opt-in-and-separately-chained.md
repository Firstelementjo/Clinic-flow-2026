# ADR 0023 — The platform console is an opt-in, separate filter chain

- **Status:** Accepted
- **Date:** 2026-10-13
- **Supersedes/amends:** [ADR 0020](0020-platform-operator-principal.md)
- **Phase:** Clinic platform — Stage 1b

## Context

ADR 0020 established that the platform operator is a distinct principal, not a tenant role, and
that it must not share an authentication path with the customer application. Stage 1b put that on
the wire: a filter that resolves the operator cookie, a controller under `/api/v1/platform/**`, and
the console operations.

Three things about that wiring were not settled by ADR 0020, and all three turned out to matter.

### 1. A platform operator holds no tenant permissions

The first implementation registered the operator filter on the customer filter chain and relied on
the controller to check for a platform principal. This does not work. Spring Security evaluates
`anyRequest().authenticated()` *before* the controller runs, and a platform operator authenticates
with no tenant permissions, so every console route answered **401** and the controller's check was
never reached. The console could not be used at all.

The alternative — permitting `/api/v1/platform/**` in the customer chain — would have worked, and
it would also have put both credentials through the same authentication machinery. That is the
coupling ADR 0020 exists to prevent, so it was rejected.

### 2. A `@Bean`-created controller has no routes

With the console wired as plain `@Bean` methods, every request returned 404 while the beans existed
perfectly in the context. The bean carried `@RequestMapping`, was not proxied, and still had no
handler mapping. On Spring Framework 6.2.11, `RequestMappingHandlerMapping` does not treat such a
bean as a handler, and the failure is completely silent: no warning, no bean failure, just a 404.

This was found by dumping the handler mapping table, not by reading the code — the code looked
correct.

### 3. `@ConditionalOnProperty` is silently inert on a scanned bean

The console is opt-in so that contexts without a database (`app-bootstrap`'s smoke test) and
contexts without an encryption key are not forced to grow a requirement they have no reason to
carry. The switch was implemented as a `@Configuration` class carrying `@ConditionalOnProperty`.

That did nothing, because the console classes were *also* annotated `@Service`, `@Repository`,
`@RestController` and `@RestControllerAdvice`. Component scanning instantiates a stereotype bean
regardless of what any `@ConditionalOnProperty` on a configuration class says. The condition was not
failing; it was being ignored. The console was present in every context in the repository while the
switch read as though it were off.

## Decision

1. **The console gets its own `SecurityFilterChain`, at `@Order(1)`, matched on
   `/api/v1/platform/**`.** Spring Security evaluates chains in order and the first matching
   `securityMatcher` wins, so a platform request never reaches the customer chain. This is the same
   construction `OAuth2ResourceServerConfiguration` already uses for bearer tokens, and for the same
   reason.

   - The customer `BrowserSessionAuthenticationFilter` is not in the chain, so a clinic
     administrator's cookie is never *read* on a platform route. It cannot authenticate there
     because the code that would read it is not present.
   - The platform filter is installed only in this chain, so a platform cookie is inert on a
     customer route.
   - Within the chain, the two login steps are `permitAll` and everything else requires
     `ROLE_PLATFORM_OPERATOR`. The controller's own `PlatformOperatorContextHolder.require()`
     remains as a second gate.

2. **A customer session on a platform route answers 401, not 403.** This is the stronger answer.
   A 403 would mean the customer session was recognised and then refused; 401 means it was never
   read. The tests assert 401 for this reason.

3. **The operator keeps `@RestController`, gated by `@ConditionalOnProperty` on the class.** The
   stereotype is required for handler detection; the condition is required because component scanning
   honours `@Conditional`. The services, repositories and codec carry **no** stereotype and are
   created only by `PlatformConsoleConfiguration`.

   The invariant is therefore *no ungated stereotypes*, not *no stereotypes*, and
   `PlatformConsoleDisabledTest` asserts it directly. Re-adding `@Service` to a console class would
   not fail any behavioural test; it would only make the switch inert.

4. **Disabled means absent.** With the console off, `/api/v1/platform/**` has no handler at all and
   falls through to the unmapped-path 404 contract. Forgetting the flag yields a console that is
   absent, not one that is open.

## Consequences

- Two contexts are affected by the switch, and only when it is on: the platform beans need a
  `NamedParameterJdbcTemplate` and `firstelement.security.encryption-key`. `PlatformSecretCodec`
  still refuses a missing or malformed key rather than falling back to plaintext.
- The console now answers with `SecurityErrorRenderer`, the same structured error body as the rest
  of the product, instead of Spring's default empty 403.
- CSRF is **not** exempted for the platform login steps. Step two acts on the cookie step one just
  set, so a cross-site induction could otherwise ride it. This is covered by a test.

## What this ADR deliberately does not settle

Whether the platform status surface carries actions (still open in `docs/clinic/open-decisions.md`),
and the nine clinic decisions that block downstream phases. Neither is affected by this wiring.