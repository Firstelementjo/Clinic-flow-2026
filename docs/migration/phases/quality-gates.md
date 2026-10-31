# FirstElement quality gates

**Owner:** Agent L (quality, security-regression, reliability)
**Status:** live from 2026-10-04
**One command:** `.\ops\quality\run-gates.ps1`

This document is the contract between the gates and the phases. Every stage
below has one command, one evidence artifact, and one owner. A phase is not
green until the stages its scope touches have run and passed.

---

## 1. Why a single runner

There is one runner, `ops/quality/run-gates.ps1`, and a developer and CI
both call it. `.github/workflows/quality-gates.yml` contains no gate logic:
it resolves a stage, calls the runner, and uploads whatever evidence the
run produced. `check-scripts.ps1` fails the pipeline if this workflow ever
stops calling the runner, because a workflow that reimplements the gates
drifts from the ones developers run and the two verdicts stop meaning
anything.

The stage catalogue is `ops/quality/gate-stages.json`. Adding, removing or
reordering a stage is a reviewable diff in one file.

```powershell
.\ops\quality\run-gates.ps1 -ListStages          # what exists, and why
.\ops\quality\run-gates.ps1                      # everything, in order
.\ops\quality\run-gates.ps1 -Stages architecture,openapi-contract
.\ops\quality\run-gates.ps1 -Presets non-gradle  # everything except Gradle
.\ops\quality\run-gates.ps1 -DryRun              # resolve commands, run nothing
```

### Evidence

Every run writes a bundle under `build/quality-evidence/<UTC timestamp>/`:

| Artifact | What it is |
| --- | --- |
| `evidence.json` | machine-readable: every stage, every command, exit code, duration, test counts, findings, verdict |
| `evidence.md` | the same thing, readable at 3am; includes the exact reproduction command |
| `latest.txt` | path of the newest bundle |
| `stages/<stage>/*.log` | stdout, stderr and exit code of every child process |
| `stages/<stage>/*.json` | whatever the stage's checks emitted (rule-by-rule findings, totals) |

`build/` is already in `.gitignore`, so evidence never reaches a commit.

---

## 2. The stages

Order is `plan.md §7.1`. Cheap and static first, so a typo is reported in
seconds instead of after a four-minute build. The database-bound stages come
next. The cross-cutting validators run after the artifacts they read. The
destructive rehearsal runs last, so nothing follows it that could need
restoring.

### 01 — `format-static`

| | |
| --- | --- |
| Owner | Agent L |
| Command | `check-scripts.ps1`, `selftest.ps1`, `git diff --check` on the owned trees |
| Evidence | `stages/format-static/scripts.json`, `checks-selftest.json` |

Rules: `FE-SCRIPT-001` every `.ps1` in the repository parses (PowerShell
AST); `FE-SCRIPT-002` the workflow is structurally sound and calls the
runner and has an `if: always()` evidence upload; `FE-SCRIPT-003` no
trailing whitespace in owned trees; `FE-HYGIENE-001` no `.env` read;
`FE-HYGIENE-002` no credential-shaped literal committed.

`selftest.ps1` is the part that makes the other checks trustworthy: it
provokes each failure mode the checks exist to detect and asserts the exact
exit code **and** a marker string in the output, so a crash in a check
cannot masquerade as a correct failure. Twelve cases, no Gradle, no Docker,
no database, about ten seconds.

### 02 — `legacy-node-static`

| | |
| --- | --- |
| Owner | Agent L |
| Command | `check-legacy-lint.ps1` → `yarn typecheck` + `yarn lint` |
| Evidence | `stages/legacy-node-static/legacy-node-static.json` |

`yarn typecheck` must exit 0 with **no baseline**: Phase 0 fixed the three
implicit-`any` parameters, so implicit `any` is a defect, not accumulated
debt.

`yarn lint` is **ratcheted** against `tests/contracts/lint-baseline.json`
(118 errors / 39 warnings, from `PROJECT_REQUIREMENTS §2`). A count above
the pin fails. A count below it prints an instruction to tighten the pin.
The tool never raises the pin itself, and no rule is disabled or ignored:
that is the difference between a ratchet and a suppression.

### 03 — `backend-compile`

| | |
| --- | --- |
| Owner | backend module owners |
| Command | `gradlew compileJava compileTestJava --rerun-tasks --no-build-cache --no-daemon --console=plain` |
| Evidence | `stages/backend-compile/*.log` |

### 04 — `backend-unit`

| | |
| --- | --- |
| Owner | backend module owners |
| Command | `gradlew :backend:shared-kernel:test :backend:tenant-security:test :backend:app-bootstrap:test --rerun-tasks --no-build-cache --no-daemon` |
| Evidence | `stages/backend-unit/*.log` + the JUnit XML the modules wrote |
| Attribution | suites, tests, failures, errors, skipped per stage |

These are the modules whose suites need no container. `crm-core` also holds
pure suites but runs in stage 06 because it also holds container suites;
`tests/contracts/expected-suites.json` is the authority on coverage, not the
module list.

### 05 — `architecture`

| | |
| --- | --- |
| Owner | Agent L |
| Command | `check-architecture.ps1` |
| Evidence | `stages/architecture/architecture.json` (rule-by-rule) |

Rules and what each one is for:

| Rule | Fails when |
| --- | --- |
| `FE-ARCH-001` | a main-scope module dependency is not permitted (`plan.md §1.3`: the arrow direction is the architecture) |
| `FE-ARCH-002` | a `backend/*` module has no `build.gradle.kts`, or is not in `settings.gradle.kts`, or is registered but absent — an unregistered module is never built or tested |
| `FE-ARCH-003` | a module whose tests import `org.testcontainers` does not pin `extra["testcontainers.version"]` (Phase-4 defect: 1.21.3 fails with a Docker error that has nothing to do with the module) |
| `FE-ARCH-004` | a main-scope cycle exists, or a test-scope edge closes one |
| `FE-WEB-001` | a class in a `*.web` package imports JDBC / `java.sql` |
| `FE-WEB-002` | a `@RestController` lives outside a `*.web` package (`@RestControllerAdvice` is an exception handler and is not a controller) |
| `FE-SEC-002` | a controller binds a client-supplied tenant identifier. A client may never *name* the tenant: it comes from `TenantContext` |
| `FE-SEC-001` | production source references `.env` |
| `FE-LOG-001` | `System.out` / `System.err` in production source |
| `FE-LOG-002` | a log call passes a **secret-named expression as an argument**. A secret word inside a message literal is documentation; only an argument can leak a value |
| `FE-TIME-001` | `Instant` / `OffsetDateTime` / `ZonedDateTime` in a `*.persistence` package (ADR-0002: the 98 `timestamp without time zone` columns bind as `LocalDateTime`) |
| `FE-WEB-003` | **advisory**: a controller that binds a `projectId` names no permission at the HTTP edge. Enforcement is on the service (Phase 3/4 decision, proven by the cross-tenant integration tests); what is missing is that the route says which permission it needs. Recorded, not failed — Phase 7 owns it |
| `FE-TENANT-001` | **ratchet**: SQL fragments whose tenant predicate is not lexically visible may not increase past the recorded ceiling (19, `tests/contracts/architecture-baseline.json`) |

`FE-TENANT-001` is a ratchet rather than a boolean on purpose. The predicate
is frequently appended by `CrmQueryScope.select(...)` or `ScopedWhere`
rather than written inside the literal, so a text scan cannot judge it
soundly — `crm-core CaseRepository.QUESTION_SELECT` is a good example of a
fragment that looks unscoped and is not. Deciding it properly needs a
call-graph engine. Until a backend-owned Gradle module exists, the count is
ratcheted and every new fragment is triaged by hand. Lowering the ceiling is
a free improvement and the check says so.

### 06 — `backend-integration`

| | |
| --- | --- |
| Owner | backend module owners |
| Command | `docker version`, then every Testcontainers module with `--rerun-tasks --no-build-cache --no-daemon` |
| Evidence | `stages/backend-integration/*.log` + per-stage test counts |

A missing Docker engine fails the stage. Testcontainers without Docker
produces JUnit files full of `<skipped/>`, which the skip gate would reject
— but the message would be about a skip rather than about the missing
service, and that misdirection is the thing this gate exists to prevent.

### 07 — `openapi-contract`

| | |
| --- | --- |
| Owner | Agent L + backend module owners |
| Command | `check-openapi-contract.ps1`, `check-error-contract.ps1` |
| Evidence | `stages/openapi-contract/openapi-contract.json`, `error-contract.json` |

Route inventory: `tests/contracts/openapi-paths.json` is generated from the
controllers by `export-openapi-paths.ps1`. The check fails when a route was
added or removed without the inventory changing, when a route is outside
`/api/v1` and not in the documented allow-list (only the connector webhook
surface), and when a path repeats a literal segment — the signature of a
method annotation carrying an absolute path under a class-level
`@RequestMapping`, which routes nowhere and is invisible in review.

Error contract: every `@RestControllerAdvice` builds the shared
`ErrorResponse`, `ErrorResponse` declares `code` / `message` / `requestId`,
a `CorrelationIdFilter` exists so `requestId` is never absent, no advice
message interpolates a resource identity (the existence oracle `FR-2.7`
forbids), and no advice returns `ResponseEntity<Map<...>>`.

### 08 — `security-tenant-negative`

| | |
| --- | --- |
| Owner | Agent L + the security agent |
| Command | `tests/security/check-negative-spec.ps1`, `check-architecture.ps1` |
| Evidence | `stages/security-tenant-negative/negative-spec.json` |

`plan.md §7.2`'s sixteen critical negative tests are an executable
specification in `tests/security/negative-spec.json`: **11 enforced, 5
staged**. Each entry either points at the suite that already proves it
(referencing is the correct disposition — a second copy would drift, and a
weakened copy would be worse than none) or names the module and phase that
will own it. The check fails when:

- one of the sixteen `plan.md §7.2` items has no entry;
- a requirement's text is a paraphrase rather than the plan's wording
  (a paraphrase is how a requirement quietly changes meaning);
- an `enforced` entry names a suite that is not in
  `tests/contracts/expected-suites.json` (the suite was renamed or deleted);
- a named test case is not in the JUnit report the run produced;
- an entry is both enforced and staged;
- a `staged` entry has no owning module, phase, or gate stage.

### 09 — `worker-idempotency-recovery`

| | |
| --- | --- |
| Owner | worker/channel agent + Agent L |
| Command | `check-no-hidden-skips.ps1` over `channels` and `worker-runtime` |
| Evidence | `stages/worker-idempotency-recovery/no-hidden-skips-worker.json` |

Re-asserts over the reports stage 06 produced rather than re-executing: the
behaviour is the same tests, run once. The value of a separate stage is that
a red worker / idempotency finding is its own required status and cannot be
hidden behind a green aggregate build.

### 10 — `export-finance` — **STAGED, RED**

| | |
| --- | --- |
| Owner | finance and export agents |
| Requires | `backend/reporting-finance`, `backend/exports` |
| Reason | Phase 8 / Phase 9. `NEG-04`, `NEG-05` and `NEG-14` cannot be enforced until they exist |

This stage fails while its modules are absent, and that failure is the
correct state of the world. `plan.md §10` requires no hidden skips; a gate
for a module that has not been built must be red, not green.

### 11 — `angular-component` — **STAGED, RED**

Requires `frontend/`. Phase 11. The command is already wired so the moment
the Angular workspace lands the gate runs.

### 12 — `playwright-journeys` — **STAGED, RED**

Requires `frontend/playwright.config.ts` or `e2e/playwright.config.ts`.
Phase 11. A browser suite that silently does not exist is exactly the
failure this stage exists to prevent.

### 13 — `performance-smoke`

| | |
| --- | --- |
| Owner | Agent L |
| Command | `tests/performance/run-smoke.ps1` |
| Evidence | `stages/performance-smoke/performance.json` |
| Dataset | `tests/performance/dataset.md`, `tests/performance/dataset.sql` |
| Budgets | `tests/performance/thresholds.json` (from `PROJECT_REQUIREMENTS §7`) |

Records p50/p95/p99 per scenario, error rate, process memory, PostgreSQL
connections in use versus `max_connections`, and queue lag (pending
`outbox_events` plus the age of the oldest pending row), with the dataset
and the command recorded alongside.

It **never skips**. No `-TargetBaseUrl`, no `-SessionCookie`, an unreachable
target, or an unreadable telemetry source all fail with the exact command to
fix it. A performance claim without a dataset and a command is not evidence,
and a performance gate that reports "skipped" turns "we do not know" into
"it is fine".

### 14 — `migration-verification`

| | |
| --- | --- |
| Owner | database agent + Agent L |
| Command | `check-disposable-targets.ps1`, verification bundle before, `prepare-throwaway-db.ps1 -MigrateChain`, verification bundle of the throwaway (`-ThrowOnViolation -ExpectedEnumCount 27`), verification bundle after, `check-dev-database-untouched.ps1`, then drop the throwaway in `alwaysCommands` |
| Evidence | `stages/migration-verification/**` |

Three independent refusals keep `firstelement` out of harm's way: the
throwaway name must differ from the protected database, it must *look*
disposable (`check` / `rehearsal` / `gate` / `throwaway` / `scratch` /
`tmp`), and it is created `TEMPLATE template0`. The whole Flyway chain is
applied in **one** psql session, which is how Phase 5a found that
`V20260814000100` depended on the baseline's `search_path`.

The stage then proves the development database was not modified: the V1
schema fingerprint and the V2 row-count map must be byte-identical before
and after, and the V3 enum contract must still hold. The disposable database
is dropped in `alwaysCommands`, so it goes away even when the stage fails.

### 15 — `restore-rehearsal`

| | |
| --- | --- |
| Owner | database agent + Agent L |
| Command | `backup.ps1` (read-only `pg_dump -Fc` + SHA-256 manifest), `prepare-restore-rehearsal.ps1` (refuses a non-disposable target, restores into `firstelement_restore_check`, verifies, `-Dispose`), `check-dev-database-untouched.ps1` |
| Evidence | `stages/restore-rehearsal/**` including `restore-checks.json` |

A backup nobody has restored is a hypothesis. The rehearsal target name is
required to contain `restore_check` or `rehearsal`, and the wrapper refuses
it outright otherwise.

### 16 — `no-hidden-skips`

| | |
| --- | --- |
| Owner | Agent L |
| Command | `check-no-hidden-skips.ps1` over every backend module's JUnit output |
| Evidence | `stages/no-hidden-skips/no-hidden-skips.json` |

Cross-cutting, runs last, and not optional. See §3.

---

## 3. No hidden skips

`AGENTS.md` ("A skipped integration, security, browser, migration or restore
test is a failure"), `plan.md` rule 10, and `plan.md §10`. The check fails
when:

1. the results directories contain **no JUnit XML at all** — a stage that
   ran nothing has not passed;
2. any suite reports `skipped > 0`, counted twice: from the
   `testsuite/@skipped` attribute *and* from the `<skipped/>` elements;
3. those two counts **disagree** — a report that mis-states its own skips is
   worse than one that states them;
4. a suite required by `tests/contracts/expected-suites.json` is missing from
   a module that was in scope;
5. a required suite ran fewer tests than its `minTests` floor;
6. a JUnit file cannot be parsed;
7. an allow-list entry is malformed, blanket, expired, or unused.

`minTests` is the last number a human reviewed (the one in the phase report
that introduced the suite), not the current count. A suite that loses tests
needs a decision, not a smaller number.

### The allow-list

`ops/quality/waivers/no-hidden-skips.allow.json`. An entry names **exactly
one** suite and **exactly one** test case, a reason of at least ten
characters, a ticket, and an `expiresOn` no more than 30 days out.

- a wildcard in either field is rejected outright — there is no blanket
  waiver, by construction;
- an entry that matches nothing **fails** as stale, because a waiver nobody
  exercises is how a temporary exception becomes permanent;
- an expired entry fails even if it would otherwise match;
- a waiver suppresses the *failure*, never the *fact*: the skipped test
  still appears in `evidence.json` as `waivedSkipCount`.

`ops/quality/waivers/pending-stages.allow.json` is the separate mechanism for
a stage whose **module** does not exist. `-AllowPendingStages` does nothing
without a matching unexpired entry, so a missing module cannot be waved
through by a flag.

### A nested suite is addressed by its display name

A JUnit `@Nested` suite is reported under its `@DisplayName`, not its class
name: the file is `TEST-...NonHttpBoundaryTest$AiTools.xml` but
`testsuite/@name` is `AI tool execution is application-owned and
deny-by-default`. `expected-suites.json` therefore records both (`suite` for
traceability, `reportName` for what appears in a report and in a waiver), and
both checkers accept either. Nine nested suites are affected today.

**Recommendation for the security and worker owners:** give every `@Nested`
class an explicit, stable id, so a reported name cannot drift with a
copy-edit.

---

## 4. Known limitations

Stated plainly rather than discovered later.

| Limitation | Why | Follow-up |
| --- | --- | --- |
| The architecture gate is a source-level rule set, not ArchUnit | adding a Gradle module needs `settings.gradle.kts` and a `build.gradle.kts`, which another agent owned during Phase 5b. The rules are written so they can be lifted verbatim | a backend-owned `:backend:architecture-tests` module; the phase owner is the orchestrator |
| `FE-TENANT-001` is a ratchet, not a proof | a text scan cannot follow `CrmQueryScope.select(...)` | ArchUnit, PMD, or an explicit scope marker in the SQL helpers |
| The OpenAPI **schema** half is not enforced | it needs a booted application to diff springdoc's document | a contract test against a booted API, in stage 07, once one is bootable in CI |
| Three stages are red by design | export/finance, Angular and Playwright modules do not exist | each closes when its module lands; the waiver is removed in the same commit |
| The performance stage needs a booted API and a seeded database | there is no application to measure yet | Phase 11 wires `docker compose` + the seed; the harness is already the command |
| `check-legacy-lint.ps1` has not been executed in this phase | it runs `yarn typecheck` and `yarn lint`; the orchestrator runs it with the Node stages | first run should be recorded in the Phase-5b or 5c report |

---

## 5. What a phase must run

| Phase touches | Must be green |
| --- | --- |
| any code | 01, 02, 03, 16 |
| a new or changed module | 01, 02, 03, 04 or 06, 05, 16 |
| a controller, DTO or error path | 07 |
| tenant scope, security, AI tools, realtime, jobs | 08, 09 |
| the outbox, connectors, workers, idempotency | 09, 06 |
| finance or exports | 10 |
| Angular | 11, 12 |
| migrations | 14 |
| backups, restore, rollback | 15 |
| anything user-visible at volume | 13 |

`AGENTS.md` rule 22 and `plan.md §10` still hold: every phase must leave the
repository buildable, and no phase is green without the tests its scope
requires — including the negative security tests.