# Frontend — the Angular client

This is the Phase 11 target-stack replacement for `apps/web` (the React/Vite
client, which stays frozen and runnable until cutover). Angular standalone
components, lazy routes, signals, and a typed client generated from the Spring
OpenAPI document.

## What is here

```
src/app/core/     the platform: api, auth, i18n, realtime, state, format, theme
src/app/ui/       shared design-system components (panels, async states, chips)
src/app/shell/    the application shell: rail, header, account, locale, theme
src/app/features/ one directory per route
e2e/              the Playwright acceptance gate (ar/en x desktop/tablet/mobile)
tools/            the client generator and its drift check
```

Route groups 1-4 (workspace, shell, login + settings, dashboard) are delivered.
Groups 5-12 land in `app.routes.ts` as real routes as they are built; the shell
marks the ones that are not there yet rather than hiding them, so the strangler
window is visible.

## Commands

```bash
npm run typecheck        # app, spec and e2e tsconfigs
npm test                 # unit and component tests (vitest via the Angular builder)
npm run build            # production bundle
npm run generate:api     # regenerate the typed client from tests/contracts/openapi.json
npm run generate:api:check  # fail if the committed client is stale
npm run e2e              # the Playwright gate (needs a running API + dev server)
```

## The typed client

Generated from `tests/contracts/openapi.json` — the document the running Spring
application serves, promoted by `ops/quality/checks/export-openapi-document.ps1`
and kept honest by `check-openapi-document.ps1`. **Never** from
`openapi-paths.json`, which is a route inventory with no schemas.

Types only (`sdk: false`): every request goes through Angular's `HttpClient` so
the CSRF header interceptor, the correlation-id interceptor and the
error-contract interceptor apply to it by construction. A generated `fetch` SDK
would bypass that chain.

`generate:api:check` compares a freshly generated tree against the committed one
by content hash. The obvious implementation — `git status --porcelain` on the
generated directory — silently passes in a fresh workspace and reports false
positives on line-ending normalisation, so it is not used.

`api-contract.spec.ts` records five **defects in the committed document** as
assertions. They are real, they belong in the backend, and they are asserted
rather than described so a fix turns the test red instead of leaving a comment
that has quietly stopped being true.

## Running the acceptance gate

The journeys drive a **real** backend. There is no mock, no recorded fixture, and
no default credential, and none is committed — a skipped browser test is a
failure, not a pass.

```powershell
# 1. A disposable database, with the Flyway chain applied.
.\ops\quality\checks\prepare-throwaway-db.ps1 -Database firstelement_e2e_scratch

# 2. The API against it.
#
#    The `--firstelement.inbox.mock-connector-enabled=true` argument is required by
#    the inbox journeys and must come AFTER the jar path, or the JVM rejects it.
#    It enables the backend's own inbound simulator
#    (POST /api/v1/dev/mock/incoming-message), which is in the committed contract
#    and drives the real pipeline. The application never calls it; only the E2E
#    fixture does, to put a real conversation in the queue. The controller also
#    requires an enabled MOCK channel connection in the project, which
#    `npm run e2e:seed` writes.
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/firstelement_e2e_scratch"
java -jar backend\app-bootstrap\build\libs\firstelement-api-0.1.0.jar `
     --firstelement.inbox.mock-connector-enabled=true

# 3. The dev server.
npm start

# 4. The gate. The credential is a throwaway account you create, not a value you
#    are given: the seed writes a bcrypt hash of it and prints nothing.
#
#    FE_E2E_SEED=1 makes global setup tear down the previous tenants and seed six
#    fresh ones — one per matrix combination, so each combination signs in as its
#    own account. A run that inherits an earlier run's conversations fails, because
#    the inbox journeys open what is waiting and an earlier journey closed some.
$env:FE_E2E_EMAIL = "e2e-admin@firstelement.test"
$env:FE_E2E_PASSWORD = "<a throwaway password>"
$env:FE_E2E_SEED = "1"
npm run e2e
```

**Known backend limitation: five projects, not six.** The customer-reference
allocator derives a CX id from the *project-scoped* customer count while
`customers."cxId"` is *globally* unique, and gives up after five attempts — so at
most five projects in a deployment can receive their first customer. The six
matrix combinations are six projects, so the sixth one's inbox and people
journeys cannot create a conversation and report a named `BackendLimitation`.
This is Phase 11 hook 11, it is a backend defect rather than a harness problem,
and no journey is skipped or weakened for it.

`e2e:seed` refuses any database whose name is not disposable and refuses
`firstelement` outright. `e2e:teardown` removes every tenant it created, by
slug, and then verifies nothing survived — so `seed → teardown → seed` is a round
trip that cannot leave a tenant behind for the next run to collide with. Both
tools derive their ids **and their slugs** from the same email, which is what
keeps them in agreement; an earlier version guarded its inserts on the primary key
only, while the schema's real uniqueness is on `slug` and `email`, so a second
seed raised a duplicate-key error against a database that looked empty.

The suite is one Playwright project per locale and width — `ar-desktop`,
`ar-tablet`, `ar-mobile`, `en-desktop`, `en-tablet`, `en-mobile` — so a failure
names the direction and the breakpoint, and `--project ar-mobile` runs exactly
one combination.

### Three things the suite has to live with

**The login rate limiter.** `LoginRateLimiter` allows 10 attempts per 60 seconds
per source address, and clears only the *account* bucket on success, so a
full-matrix run legitimately trips it. Each of the six matrix projects signs in as
its **own throwaway account**, so the account bucket — the one that refuses a
legitimate retry — never saturates. The production limit stays at 10/60s.
`Journey.signIn()` keeps a bounded retry as a backstop for the per-address bucket,
which is shared and deliberately never cleared by a success; see the comment on
`e2e/matrix.ts` for why pacing sign-ins was rejected as the fix.

**The CSRF token rotates.** The server rewrites the `XSRF-TOKEN` cookie on every
successful unsafe request, so a token captured before the previous mutation is
refused. The application reads the cookie per request rather than caching it;
anything driving the API outside `HttpClient` has to do the same.

**The seed's identity follows the email.** `e2e/seed.ts` derives the organization
id, project id *and* the slugs from one email, and guards each insert on the key
the schema really enforces (`slug`, `email` — not only the primary key). Teardown
sweeps the whole `e2e-` slug prefix and then verifies nothing survived, so
`npm run e2e:seed → npm run e2e:teardown → npm run e2e:seed` is a round trip that
cannot leave a tenant behind for the next run to collide with.

## Rules this codebase holds itself to

Enforced by tests rather than by convention, because a convention nobody runs is
a convention nobody keeps:

- **No raw colours** outside `src/styles/tokens.css`; both themes defined
  separately (`design-system.spec.ts`).
- **No physical directional CSS** — `margin-left`, `left:`, `text-align: right`
  and friends are rejected, so RTL works from one stylesheet.
- **No `display: none` on a label** in a responsive block. It strips the
  accessible name as well as the layout; the clip pattern is used instead.
- **No emoji, no all-caps copy** in templates or the dictionary.
- **No hard-coded user-facing strings.** `i18n.spec.ts` walks the shipped sources
  and fails if a literal key does not resolve, and checks the runtime-assembled
  key families (`shell.status.`, `shell.channel.`) that a per-key check cannot
  see.
- **Never a long-lived bearer token in the browser.** The session is an HttpOnly
  cookie; CSRF comes from the cookie/header pair, both names read from the wire.
- **Route guards are UX only.** The server decides; a guard decides nothing that
  is not re-checked there.
- **Realtime is a refresh hint.** A frame may carry ids only, so the payload is
  optional at every call site and every signal produces a refetch. After a
  reconnect the client resynchronises from the API rather than trusting anything
  the stream did or did not deliver.
- **No mock that ships.** Every screen reads the real API and has designed
  loading, empty, error and stale states.
- **Never edit a source file through a shell pipeline.** Windows PowerShell 5.1
  decodes a BOM-less file with the system ANSI code page, so a
  `Get-Content -Raw | … | Set-Content` round-trip silently turns every em dash,
  `⇄` and `§` into mojibake — and the file still compiles and the tests still
  pass, because the damage lands in comments. Use the editor's read and write
  tools. `encoding.spec.ts` fails the build on U+FFFD, on stray C0 control
  characters and on a BOM, and asserts the Arabic dictionary still contains
  Arabic script with its diacritics, so the fault cannot ship silently again.
