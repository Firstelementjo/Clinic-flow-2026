# Phase 34 — incident forensics: making "was it exploited?" answerable

Status: **tool authored, rehearsed and mutation-tested. The question is
still NOT answered**, and the honest reason is recorded in §2 rather than
papered over.

| | |
|---|---|
| Trigger | The legacy cross-tenant clinic breach (ledger §8, phases 24–26, 33). Exploitation unknown and, for the roster paths, unknowable from this workspace |
| Scope | `ops/forensics/**` only. **No production code, no schema, no migration, no runtime behaviour changed** |
| Production change | **none** |
| Migration impact | **none** |
| Verification | `Rehearse-ForensicScan.ps1` — 24 scenarios, 0 skipped, plus 3 mutation tests proving the rehearsal can go red |
| The finding that matters | The one bounded lead the ledger named **cannot fire on the only database in this workspace**, because that database contains **zero** appointment lifecycle audit rows. This is reported, not worked around |
| Reported, not fixed | `customerVisibleRequiresApproval` default; roster writes unaudited after Phase 33; pre-existing `check-disposable-targets.ps1` false positive |

---

## 1. What was built

```
ops/forensics/
  sql/F-cross-tenant-access.sql            the query: 8 findings + 7 coverage counters
  Invoke-ForensicScan.ps1                  the guarded runner (refuses, re-reads, judges)
  Rehearse-ForensicScan.ps1                24 scenarios, mutation-tested
  fixtures/seed-clean-single-tenant.sql    positive control: known-clean
  fixtures/seed-contaminated-cross-tenant.sql  negative control: one instance of each signal
  README.md                                what it proves / cannot prove / how to run
  RESPONSE.md                              what to do on a positive result
  .gitignore                               rehearsal artefacts are never committed
```

The query's core is the ledger's bounded lead:

```sql
FROM audit_logs l JOIN appointments a ON a.id::text = l."resourceId"
WHERE l.action IN ('APPOINTMENT_CREATED','APPOINTMENT_RESCHEDULED',
                   'APPOINTMENT_CANCELLED','REMINDER_SENT')
  AND l."organizationId" <> a."organizationId"
```

Real column names were read from `information_schema` before anything was
authored. They are not the snake_case the repository's own migration files
use — `audit_logs."resourceType"`, `appointments."organizationId"`,
`customers."cxId varchar(9)` — and guessing them would have produced a
query that fails on contact.

## 2. The headline: the lead cannot fire here

Running the query read-only against the live `firstelement` database:

```
AUDITED_ACTION_COUNT:USER_LOGGED_IN=41
AUDITED_ACTION_COUNT:USER_LOGGED_OUT=2
TABLE_ROW_COUNT:APPOINTMENTS=1
APPOINTMENTS_WITH_NO_AUDIT_ROW_AT_ALL=1 of 1
ROSTER_AUDIT_ACTION_ROWS=0
```

**The entire audit trail is login events.** There is not one
`APPOINTMENT_*` row, so there is nothing for the cross-tenant join to
compare, and nothing at all for the roster paths.

This is precisely the failure mode the brief warned about, and it is the
reason the tool has three verdicts rather than two. On this database it
returns **`INCONCLUSIVE`, exit 3** — explicitly not exit 0 — and says
`NO AUDIT COVERAGE` on the face of the report. A tool that printed
"CLEAN" here would be manufacturing false assurance about a patient-data
incident.

Note this is the **development** database (one organization, 4 users, 43
audit rows since 2026-09-01), not production. The structural point stands
regardless: whether the real production trail covers the breach window is
a fact about production, and only whoever owns that database can read it.

## 3. Signals beyond the named lead

The schema supports more than the one join, and three of these reach paths
the audit join cannot:

| Id | Why it was added |
|---|---|
| `FE-FOR-002` | Cross-**project** breach inside one tenant. `FE-FOR-001` compares organizations and is blind to it by construction |
| `FE-FOR-003` | An audited appointment whose target row is gone. **The only delete signal the schema supports** — deletes write no audit row |
| `FE-FOR-004` | Audit row's organization vs the **actor user's** organization, for *any* action in the system — not just the clinic module |
| `FE-FOR-005` | An appointment referencing a doctor/procedure/customer/project in another org. Detects a cross-tenant **write** with no audit row at all |
| `FE-FOR-006` | A doctors/procedures/tasks row whose org disagrees with its project's org. **This is the "doctor moved into another tenant" signature**, and the `tasks.update` cross-tenant write |
| `FE-FOR-008` | Audit actor without membership in the audited project — the exact "no permission, membership or project access" principal the breach describes |

`FE-FOR-005` and `FE-FOR-006` are the important addition: they find
**persistent residue**, so they can detect a cross-tenant write to the
roster even though roster writes leave no audit trail. That is the
honest partial answer to the doctor/procedure question — *state*, not
*history*.

## 4. Test results

All commands Windows PowerShell 5.1, `docker exec` into
`firstelement-postgres` (pgvector/pgvector:pg16).

| Scenario | Assertion | Result |
|---|---|---|
| R1 | refuses `-Database firstelement` | pass (exit 2) |
| R2 | refuses a name without `forensic` | pass (exit 2) |
| R3a | `INSERT` rejected under the scanner's session settings | pass |
| R3b | **the same `INSERT` succeeds without them** (control) | pass |
| R3c | scanner self-reports `sessionReadOnly=on` | pass |
| R4a/R4b | refuses a query selecting `patientName` / `metadata` | pass (exit 2) |
| R5a–d | clean fixture → exit 0, 0 findings, coverage present, roster blind spot still printed | pass |
| R6a + 8 | contaminated fixture → exit 1 and **all eight checks fire** | pass |
| R7a–c | zero coverage → exit 3, `INCONCLUSIVE`, `NO AUDIT COVERAGE` printed | pass |
| R8 | no patient/doctor/procedure/actor/tenant name in any rendered report | pass |

**24/24 passed, 0 skipped.**

### Read-only, measured

```
$ <INSERT ...> | docker exec -i -e PGOPTIONS=-c default_transaction_read_only=on ... psql
ERROR:  cannot execute INSERT in a read-only transaction          exit 3
$ <same INSERT> | docker exec -i ... psql
INSERT 0 1                                                          exit 0
```

The control is the point: without it, a rejected statement proves nothing.

### Mutation testing — proving the rehearsal can go red

| Injected defect | Result |
|---|---|
| Delete the `FE-FOR-006` check from the SQL | **caught** — `R6-006` failed, 22/23 |
| Make the scanner report `CLEAN` at zero coverage | **caught** — `R7a`/`R7b` failed, 21/23 |
| Remove the scanner's read-only session guard | **MISSED on first run** (23/23) → fixed → **caught**, 20/24 |

That third row is the honest part. R3 originally proved *PostgreSQL*
honours the setting, not that *this scanner* ran under it — two different
claims. A scanner that lost its guard still produced a perfectly
plausible report and the whole rehearsal stayed green. The fix is
`FE-FOR-C07`: the query reports its own `default_transaction_read_only`,
the scanner refuses to report anything but `on`, and `R3c` asserts it.

## 5. Guard and redaction

- **Reuses** `prepare-throwaway-db.ps1` for every disposable database; no
  second convention invented. The scanner mirrors its three refusals
  (`-ProtectedDatabase`, disposable-looking name, plain identifier) and
  adds a stricter fourth: the name must contain `forensic`.
- **Never writes.** Session-level `default_transaction_read_only=on`,
  proved above; the scanner additionally self-verifies or exits 2.
- **Never emits patient data.** Eight-column output shape only. Rule
  `FE-FOR-REDACT-001` greps the executable SQL for every clinical column
  and refuses to run on a hit — including `audit_logs.metadata`, which the
  clinic service populates with `patientPhone` and `doctorName` on
  `REMINDER_SENT`. Comments are stripped before the check so the file can
  explain the rule without tripping it.
- **Never reads `.env`** and never takes a password; local socket only.

## 6. What this does NOT prove

- **The roster question is unanswerable from history.** Doctor and
  procedure create/update/delete write no audit row, deletes are
  unaudited, and no read writes one anywhere. Only residue is visible, and
  only if it was never reverted.
- **Reads are undetectable**, by any query, forever, on this schema.
- **Unauthenticated `@Public()` access is undetectable** — no row is
  written, so "never called" and "called ten thousand times" are
  identical.
- **Intra-tenant abuse is undetectable and is the widest gap.** Before
  Phase 33 any employee could rewrite their *own* tenant's roster. That is
  a correct org-scoped write: same organization, right tables, no audit
  row. Nothing is left to find.
- `FE-FOR-004` and `FE-FOR-008` produce identical shapes for a legitimate
  tenant transfer or a membership granted after the fact. Leads, not
  conclusions.
- **The tool is not wired into CI.** `ops/quality/gate-stages.json` is
  outside this phase's ownership and was not touched; see follow-up.

## 7. What would have to change for the roster paths

Nothing in `ops/forensics` — a query cannot invent a record that was never
written. Required, in order:

1. **Audit roster writes** (`doctors`, `procedures`): actor org +
   resource id, the shape `APPOINTMENT_*` already uses.
2. **Audit deletes** with the deleted id.
3. **Audit reads of clinical data**, or route them to an access log —
   the only way the read half becomes answerable.
4. **Composite tenant FKs**: `appointments_doctorId_fkey` is a
   single-column FK on `id`; nothing prevents an appointment in tenant A
   pointing at a doctor in tenant B.
   `FOREIGN KEY (doctorId, organizationId) REFERENCES doctors(id, organizationId)`
   makes residue impossible rather than detectable.
5. **A retention decision for `audit_logs`**, so the trail exists when
   someone asks.

## 8. Reported, not fixed

- **`customerVisibleRequiresApproval` defaults to `true`**
  (`V20261005100000__ai_policy_and_governance.sql:298`). All four approval
  gates default `true`, described in-file as the conservative choice per
  ADR-0007. Reported because a default nobody has stated a decision
  about is indistinguishable from an accident, and the cost of being wrong
  is a customer-visible AI action shipping unreviewed. Not changed here:
  it is a Flyway migration file and the first Flyway-enabled boot is a
  Phase 12 act.
- **Phase 33's nine clinic permissions still leave roster writes
  unaudited.** `doctor:create/update/delete` and `procedure:*` are now
  permission-gated, so a *permission* check exists — but no audit row is
  written, so a correctly-permissioned write and an attacker's write look
  identical. A permission without an audit trail is a control that cannot
  be reviewed after the fact.
- **Pre-existing gate failure**, not mine: `check-disposable-targets.ps1`
  reports `FE-TARGET-004` against `ops/flyway/verification/rehearsal-chain.ps1:63`.
  That line is a **commented example** (`#  -Database firstelement_p710a_check`).
  `git status` shows `ops/flyway/**` and `ops/quality/**` unmodified, so
  this fails on `HEAD` independently of this phase. `ops/quality/checks/*.ps1`
  is outside this phase's ownership and was not modified.
- **`apps/worker` and `packages/**` remain unswept** (ledger §8). A worker
  writing clinic rows directly would carry the same defect and nothing
  rules it out.

---

## 9. Commands

```powershell
# full rehearsal
powershell -NoProfile -ExecutionPolicy Bypass -File ops\forensics\Rehearse-ForensicScan.ps1

# existing gates, unchanged by this phase
powershell -NoProfile -ExecutionPolicy Bypass -File ops\quality\checks\check-scripts.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File ops\quality\checks\check-disposable-targets.ps1
```