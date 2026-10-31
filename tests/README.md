# tests/ — migration test specifications and contracts

Language-agnostic test **content** for the Spring/Angular migration. The
executable gates live in [`ops/quality`](../ops/quality/README.md); this tree
holds what they assert against and the harness that does not belong to a
Gradle module.

```
tests/
  contracts/
    expected-suites.json        every suite that must produce a JUnit report, with a test-count floor
    openapi-paths.json          the committed route inventory (generated)
    architecture-baseline.json  ratchet ceilings for rules a text scan cannot decide
    lint-baseline.json          the pinned legacy lint ceiling
    check-error-contract.ps1    the stable error body
    fixtures/                   synthetic JUnit XML + waiver fixtures for the self-test
  security/
    negative-spec.json          plan.md 7.2's sixteen critical negative tests
    check-negative-spec.ps1     validates the specification against reality
  performance/
    run-smoke.ps1               the harness
    thresholds.json             the budgets, from PROJECT_REQUIREMENTS section 7
    dataset.md / dataset.sql    the synthetic dataset and its volumes
  README.md
```

## The two rules that shape this tree

**A required suite is declared, not assumed.** Anything in
`expected-suites.json` must produce a report whenever its module is in scope.
A suite that is renamed, deleted or emptied without a decision here fails the
`no-hidden-skips` gate. That is the difference between a deleted suite and a
suite that stopped running: without a manifest they look identical, and the
second one is invisible.

**Fixtures are synthetic and self-describing.** Every fixture in
`contracts/fixtures/` carries a comment saying what failure it provokes and
what the expected exit code is. A fixture that looks like real output is a
fixture someone will trust too much.

## The fixtures exist to prove the gate fails

`contracts/fixtures/junit/` holds five result sets — clean, one honest skip,
a skip hidden behind a `skipped="0"` attribute, a missing required suite, and
an empty directory — plus `contracts/fixtures/waivers/` with a valid waiver
and four invalid shapes (expired, blanket, ticket-less, stale, display-name).

`ops/quality/checks/selftest.ps1` runs all twelve combinations and asserts
the exit code **and** a marker string in the output, so a parse error in a
check cannot pass as a correct failure. The first version of this self-test
did exactly that: every "expected failure" passed because the checker had a
syntax error and exited 1. The marker requirement exists because of it.

Run it:

```powershell
.\ops\quality\checks\selftest.ps1
```

## Adding to this tree

- A new required suite → add it to `expected-suites.json` in the same commit
  that adds it, with its module, its stage and a `minTests` floor taken from
  the number a human last reviewed.
- A new negative requirement → add it to `negative-spec.json` with either an
  `enforcedBy` reference to a real suite or a `staged` owner module, phase
  and gate stage. The validator rejects anything else.
- A new performance scenario → add it to `thresholds.json` with a budget that
  is no more lenient than `PROJECT_REQUIREMENTS §7`, and to `dataset.md` if it
  needs rows.

Documentation: [`docs/migration/testing.md`](../docs/migration/testing.md)
and [`docs/migration/phases/quality-gates.md`](../docs/migration/phases/quality-gates.md).