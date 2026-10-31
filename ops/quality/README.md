# ops/quality — the gate runner and its checks

The single quality-gate runner. A developer and CI call the same thing:

```powershell
.\ops\quality\run-gates.ps1 -ListStages
.\ops\quality\run-gates.ps1
.\ops\quality\run-gates.ps1 -Stages architecture,openapi-contract
.\ops\quality\run-gates.ps1 -Presets non-gradle
.\ops\quality\run-gates.ps1 -DryRun
```

## Why the runner lives here and not in `tests/`

`tests/` holds test **content** — specifications, fixtures, the
performance harness and its dataset — in languages other than the one that
executes it. `ops/quality/` holds the **executable gates**, beside the
`ops/flyway/verification/` and `ops/backup-restore/` scripts that the gates
call. The two trees answer different questions ("what must be true?" versus
"what do we run?"), and mixing them is how a reviewer ends up unsure whether
editing a file means writing a test or changing a gate.

## Layout

| Path | What it is |
| --- | --- |
| `run-gates.ps1` | the runner: stages in order, evidence bundle, exit code |
| `gate-stages.json` | the stage catalogue. The only place a command is declared |
| `lib/quality-common.ps1` | shared helpers: process invocation with real exit codes, JUnit aggregation, percentiles, date parsing |
| `checks/check-scripts.ps1` | PowerShell syntax, workflow structure, whitespace, secret hygiene |
| `checks/check-architecture.ps1` | module layering, web boundary, logging, persistence-time, tenant-SQL ratchet |
| `checks/check-openapi-contract.ps1` | the committed route inventory must equal the controllers |
| `checks/check-no-hidden-skips.ps1` | skipped > 0, a missing required suite, or an invalid waiver all fail |
| `checks/check-legacy-lint.ps1` | `yarn typecheck` plus a ratcheted `yarn lint` |
| `checks/check-dev-database-untouched.ps1` | the dev database's fingerprint and row counts are unchanged |
| `checks/check-disposable-targets.ps1` | `firstelement` is never a rehearsal, migration or restore target |
| `checks/prepare-throwaway-db.ps1` | create a disposable database, optionally apply the chain, optionally drop it |
| `checks/prepare-restore-rehearsal.ps1` | refuse a non-disposable target, then run the existing rehearsal |
| `checks/selftest.ps1` | proves every check FAILS when it should |
| `waivers/*.allow.json` | the two allow-list mechanisms, both documented and both expiring |

## Two PowerShell 5.1 traps this tree is written around

Both were found by running the gates, not by reading them.

1. **`@($x)` throws "Argument types do not match" when `$x` is an EMPTY
   generic `List`.** An empty violations list is the normal good-path state
   of every check here. `ConvertTo-QArray` is the only sanctioned
   conversion.
2. **`ConvertTo-Json` serialises a wrapped .NET `object[]` as
   `{"value":[…],"Count":n}`** rather than as an array, which silently
   corrupts every JSON evidence document. `ConvertTo-QArray` builds its
   result with `+= ,$item` so the array is PowerShell-constructed.

A third, less obvious one: `[xml] $x = …` **type-constrains `$x` for the
rest of the scope**, so a later `foreach ($x in <string>)` fails with a cast
error about `XmlDocument` that reads like a data problem and is not one.

## Evidence

`build/quality-evidence/<UTC timestamp>/` — `evidence.json`,
`evidence.md`, `latest.txt`, and `stages/<stage>/` with each child
process's merged output and exit code. `build/` is gitignored.

## Adding a check

1. Put it in `checks/`.
2. Give it a `Rule` prefix (`FE-*`, `ERRC-*`, `FE-TARGET-*`), a
   `.SYNOPSIS` that says what it is for, and an exit code of 0 / 1 / 2.
3. Add at least one case to `selftest.ps1` that provokes the failure it
   exists to detect, asserting both the exit code **and** a marker string in
   the output.
4. Wire it into a stage in `gate-stages.json`.

A check with no self-test is a check nobody has seen fail.