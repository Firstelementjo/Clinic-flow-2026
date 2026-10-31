# .github/workflows

## `quality-gates.yml` — the required status checks

Sixteen jobs, one per stage of `ops/quality/gate-stages.json`, in the order
`plan.md §7.1` requires. Each job calls the **same runner a developer
calls**; this file contains no gate logic.

### Required status checks to configure in branch protection

Set **all sixteen** as required, plus `quality gate verdict` as the summary.
Marking only the summary as required would let the eight stages that are
still red on purpose (export/finance, Angular, Playwright, and the four data
gates until they run) be bypassed by someone who only watches the aggregate.

| Job | Stage |
| --- | --- |
| `gate 01 format + static` | script syntax, workflow structure, whitespace, secret hygiene, gate self-test |
| `gate 02 legacy node static` | `yarn typecheck` + ratcheted `yarn lint` |
| `gate 03 backend compile` | `compileJava` / `compileTestJava` |
| `gate 04 backend unit tests` | the pure-JVM modules |
| `gate 05 architecture` | module layering, web boundary, logging, persistence-time, tenant-SQL ratchet |
| `gate 06 testcontainers integration` | pgvector/PostgreSQL + Redis, Docker asserted first |
| `gate 07 openapi + contract` | route inventory + error contract |
| `gate 08 security + tenant negatives` | `plan.md §7.2` specification vs reality |
| `gate 09 worker + idempotency + recovery` | crash / retry / dead-letter / replay |
| `gate 10 export + finance reconciliation` | **red until Phase 8/9** |
| `gate 11 angular component tests` | **red until Phase 11** |
| `gate 12 playwright journeys` | **red until Phase 11** |
| `gate 13 performance smoke` | p50/p95/p99, error rate, memory, connections, queue lag |
| `gate 14 migration verification` | disposable database + dev-database immutability |
| `gate 15 backup + restore rehearsal` | backup, rehearsal, dev-database immutability |
| `gate 16 no hidden skips` | skipped > 0, a missing required suite, an invalid waiver |
| `quality gate verdict` | aggregate; always runs, always reports |

### Why one job per stage

With steps, GitHub reports the first failure and cancels the rest of the job:
a green integration stage and a red security stage collapse into one red blob.
A separate job per stage means each finding is its own status, its own log and
its own evidence directory, and a reviewer can see exactly which gate moved.

### Evidence

Every job uploads `build/quality-evidence/**` with `if: always()`. The run
you need the evidence from is the one that failed. `quality gate verdict`
writes `GATE-VERDICT.md` and attaches it too.

Stages 08, 09 and 16 **download** the evidence from stage 06 first and assert
over the JUnit reports it produced. They do not re-run those tests: the point
is a separate required status for the security / worker / skip verdicts, not
a second execution that could disagree with the first.

### Docker

Every container and data job runs `docker version` (and `docker info` where
it matters) before anything else and fails there. Testcontainers without a
Docker engine produces JUnit files full of `<skipped/>`, and the skip gate
would correctly reject them — but the message would be about a skip rather
than about the missing service, and that misdirection is what the Docker
assertion prevents.

### Permissions and triggers

`permissions: contents: read`, so a pull request (especially from a fork)
cannot write to the repository through this pipeline. Triggers on every pull
request, on pushes to `main` / `master` / `migration/**`, and manually.
`concurrency` cancels superseded runs on the same ref.

### Windows

The runners are `ubuntu-latest` and every gate shells out through `pwsh`,
which is present there. The scripts are written to Windows PowerShell 5.1 as
the floor because this repository's developer path has no `pwsh`; the runner
records which PowerShell produced a result, so a CI number and a laptop
number are never silently different instruments.