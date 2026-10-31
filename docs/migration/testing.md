# FirstElement migration — testing and evidence

This is the testing document for the **migration** (the Spring/Angular
target runtime). It is not [`docs/testing.md`](../../testing.md), which
documents the legacy Node/React runtime's Vitest suite and stays where it
is. Both matter until Release F retires the legacy runtime; this one
describes how the migration proves itself.

Companion documents:

| Document | Purpose |
| --- | --- |
| [`phases/quality-gates.md`](phases/quality-gates.md) | every gate, its command, its evidence artifact, its owner |
| [`flyway-runbook.md`](flyway-runbook.md) | the database contract: baseline, backup, restore, rollback |
| [`phases/phase-0N.md`](phases/) | what each phase proved, with exact commands |

---

## 1. The rules this repository holds itself to

These come from `AGENTS.md`, `plan.md §10` and `OPENCODE_ORCHESTRATOR.md`,
and every gate below exists to enforce one of them mechanically.

1. **A skipped test is a failed gate.** Not "skipped with a reason", not
   "allowed in this environment". Failed.
2. **A missing service is a failure, not a skip.** Docker unavailable,
   PostgreSQL unavailable, Redis unavailable: the stage fails and names the
   missing service.
3. **A cache hit is not a test result.** Every Gradle stage passes
   `--rerun-tasks --no-build-cache --no-daemon`.
4. **Every claim carries its command.** A performance number without a
   dataset and a command is not evidence; a green pipeline without an
   evidence bundle is a claim.
5. **Synthetic data only.** No `.env`, no production credential, no
   customer export, no session token is read or printed by any gate.
6. **A destructive rehearsal only ever targets a disposable database**, and
   the disposable database is dropped afterwards whether or not the stage
   passed.
7. **A gate that has only ever passed is not a gate.**
   `ops/quality/checks/selftest.ps1` provokes each failure mode and asserts
   the exit code.

---

## 2. Running the gates

```powershell
# everything, in order, with an evidence bundle
.\ops\quality\run-gates.ps1

# what exists
.\ops\quality\run-gates.ps1 -ListStages

# a subset (comma-joined; a repeated flag is a PowerShell binding error)
.\ops\quality\run-gates.ps1 -Stages architecture,openapi-contract

# everything except the Gradle stages
.\ops\quality\run-gates.ps1 -Presets non-gradle

# resolve every command without running anything
.\ops\quality\run-gates.ps1 -DryRun
```

Presets: `static`, `non-gradle`, `backend`, `data`, `full`.

Results land in `build/quality-evidence/<UTC timestamp>/` as `evidence.json`
and `evidence.md` plus one directory per stage. `build/` is gitignored.

**Windows PowerShell 5.1 is the floor.** There is no `pwsh` on the
developer path in this repository today, so every script avoids PS7-only
syntax (`??`, ternaries, `-AsHashtable`, `ForEach-Object -Parallel`,
`Test-Json`) and is tested against 5.1. The runner prefers `pwsh` when it
exists and records which PowerShell produced a result.

---

## 3. Where the tests live

| Location | Holds |
| --- | --- |
| `backend/*/src/test/java` | the real unit, Testcontainers and behaviour suites — the tests that matter |
| `tests/contracts/` | the required-suite manifest, the route inventory, the lint ratchet pin, the architecture ratchet ceiling, the error-contract check, and the JUnit fixtures the self-test uses |
| `tests/security/` | the `plan.md §7.2` negative-test specification and its validator |
| `tests/performance/` | the performance harness, its thresholds, its synthetic dataset and the dataset write-up |
| `e2e/` | Playwright journeys (Phase 11; the stage is red until then, on purpose) |
| `ops/quality/` | the runner, the stage catalogue, the checks, the waivers |
| `.github/workflows/` | one job per gate, each calling the runner |

---

## 4. Test tiers

| Tier | Where | Needs | Stage |
| --- | --- | --- | --- |
| pure unit | `shared-kernel`, `tenant-security` test sources | a JVM | 04 `backend-unit` |
| Testcontainers integration | `database`, `crm-core`, `identity-access`, `inbox-helpdesk`, `channels`, `worker-runtime`, `notifications-realtime` | Docker | 06 `backend-integration` |
| smoke (whole context) | `app-bootstrap` | a JVM, no database | 04 `backend-unit` |
| architecture | `ops/quality/checks/check-architecture.ps1` | nothing | 05 `architecture` |
| contract | `tests/contracts/` | nothing (static) / a booted API (schema, staged) | 07 `openapi-contract` |
| security negatives | the Spring suites, asserted by `tests/security/negative-spec.json` | Docker | 08 |
| worker / recovery | `channels`, `worker-runtime` | Docker | 09 |
| migration | `FlywayBaselineAcceptanceTest` + the V1–V10 bundle | Docker | 06 and 14 |
| performance | `tests/performance/run-smoke.ps1` | a booted API + a seeded throwaway DB | 13 |
| browser | Playwright | `frontend/` | 12 (red until Phase 11) |

The Testcontainers image is pinned: `pgvector/pgvector:pg16`, and every
module pins `extra["testcontainers.version"] = "1.21.4"` (rule
`FE-ARCH-003`). The Spring Boot BOM's 1.21.3 cannot negotiate with Docker
Desktop 29.x and fails with "Could not find a valid Docker environment",
which reads as a test problem when it is a dependency problem.

---

## 5. Tenant isolation and the negative suite

`FR-1` is the requirement. `plan.md §7.2` lists the sixteen critical
negative tests. They are specified in
`tests/security/negative-spec.json` and validated by
`tests/security/check-negative-spec.ps1`:

- **11 enforced** — each points at the suite that already proves it, with
  the specific `@DisplayName`s where they matter;
- **5 staged** — each names the owning module, the phase, and the gate stage
  that will enforce it (`NEG-04`, `NEG-05`, `NEG-14` → export/finance;
  `NEG-06`, `NEG-16` → AI/RAG).

The rule is **reference, never duplicate**. A second copy of a negative test
drifts; a weakened copy is worse than no copy. The validator fails when an
`enforced` entry names a suite that no longer exists, or a test case that is
not in the report — so a coverage requirement cannot quietly evaporate when
a suite is renamed.

`FE-SEC-002` adds a static half: a controller may not bind a client-supplied
tenant identifier at all. `projectId` may still appear on the wire, because
the legacy client sends it and the server validates it against the
server-resolved scope; `organizationId` and `tenantId` have no legitimate
reason to be bindable, and a bound tenant id is one refactor away from
becoming an authorisation.

---

## 6. Evidence a phase has to produce

1. `evidence.json` and `evidence.md` from a run that included every stage
   the phase's scope touches.
2. The exact command for anything not covered by a stage (for example a
   live boot, or `yarn lint`'s measured counts).
3. Row counts, fingerprints and checksums for anything data-related.
4. p50/p95/p99, error rate, memory, connections and queue lag for any
   performance statement, with the dataset (`tests/performance/dataset.md`).
5. A list of failures classified as **code**, **environment**, **data** or
   **test defect**. A failure is not a pass and is not a blocker; it has a
   class.

---

## 7. Adding a test

- A **behaviour** test goes in the owning backend module, next to the code
  it describes, named for the behaviour rather than the method.
- A new **suite** must be added to `tests/contracts/expected-suites.json`
  with its module, stage and `minTests` floor in the same commit. A suite
  that is not in the manifest is not required to run.
- A new **gate** goes in `ops/quality/gate-stages.json` and gets a job in
  `.github/workflows/quality-gates.yml`.
- A new **check** under `ops/quality/checks/` gets at least one case in
  `selftest.ps1` that proves it fails when it should.
- A new **skip** needs an entry in
  `ops/quality/waivers/no-hidden-skips.allow.json` with a reason, a ticket
  and an expiry inside 30 days — and a reviewer should ask why the test is
  skipped, not why it is not written.

---

## 8. Legacy runtime tests

The Node/React runtime under `apps/**` and `packages/**` still serves
production during the strangler window, so its tests still count:

```bash
yarn typecheck          # must pass, no baseline
yarn test               # needs DATABASE_URL / REDIS_URL or the integration suites skip
yarn lint               # ratcheted against tests/contracts/lint-baseline.json
```

`yarn lint` currently reports **118 errors and 39 warnings** and that is the
pinned ceiling, not a pass. `yarn test` needs `DATABASE_URL` and `REDIS_URL`;
without them the integration suites skip, which is precisely the condition
`AGENTS.md` forbids, so the Node side has the same gap the Spring side closed
and it is a known item for the orchestrator (the legacy Vitest configuration
has no no-hidden-skips enforcement of its own; the Spring gate only covers
JUnit XML). See `docs/migration/phases/quality-gates.md` §4.