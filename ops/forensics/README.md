# Incident forensics — `ops/forensics`

A read-only tool that answers one question as far as the schema allows:

> **Was the legacy cross-tenant clinic breach exploited?**

It answers it **partially**, and it is built so that the gap between
"nothing happened" and "I cannot see" is stated on the face of every run
rather than left for the reader to infer. That gap is the whole reason
this tool exists. A forensic report that returns zero on a database with
no audit coverage is worse than no report at all, because it manufactures
false assurance about a patient-data incident.

---

## Running it

```powershell
# 1. Take a COPY of production. Never scan the live database.
#    (see "Getting a copy" below)

# 2. Name the copy so that it is identifiable as a forensics target:
powershell -NoProfile -ExecutionPolicy Bypass `
  -File ops\forensics\Invoke-ForensicScan.ps1 `
  -Database firstelement_forensic_copy `
  -JsonOut forensics-report.json
```

### Exit codes

| Code | Verdict | Meaning |
|---|---|---|
| 0 | `CLEAN` | No mismatch found **and** coverage was sufficient to look |
| 1 | `FINDINGS` | Something crossed a tenant boundary. **Treat as an active incident** — see [RESPONSE.md](RESPONSE.md) |
| 2 | — | Refused, or the scan could not run. No report produced |
| 3 | `INCONCLUSIVE` | The query ran but the database could not support an answer. **This is not a clean result and is deliberately not exit 0** |

Exit 3 exists so that a caller checking "did it succeed?" cannot mistake
"could not tell" for "nothing found". Only `CLEAN` is a clean result,
and `CLEAN` is unreachable without coverage.

---

## What it will refuse

Reusing the guard shape from `ops/quality/checks/prepare-throwaway-db.ps1`
rather than inventing a second convention:

| Refusal | Rule |
|---|---|
| `-Database firstelement` | The protected database. This is a read-only scan, but it is still a read of the one database this repository must never touch by accident |
| Any name without `forensic` | A scan target must be identifiable in `pg_stat_activity`, in a process list and in a backup directory listing |
| Any name that is not a plain identifier | Blocks `forensics; DROP DATABASE x` |
| Any query selecting patient data | Rule **FE-FOR-REDACT-001**, below |
| Any session that is not read-only | The scanner self-verifies and exits 2 rather than producing an unverified report |

### It cannot write

The `psql` session is launched with
`PGOPTIONS=-c default_transaction_read_only=on`, so PostgreSQL itself
rejects any write. This is not a convention: the rehearsal proves it by
running an `INSERT` through the scanner's exact session settings, showing
it is rejected, then running the identical statement without those
settings and showing it succeeds. The control is what makes the test mean
anything.

The scanner additionally asserts `default_transaction_read_only = 'on'`
from inside the query and refuses to report if it is not.

### It cannot emit patient data

Every row has the same eight columns — `section`, `check_id`, `severity`,
two organization ids, a resource id, a timestamp and a **fixed
vocabulary token**. No name, no phone number, no note.

`audit_logs.metadata` is **never selected**, even to filter it: the clinic
service writes `patientPhone` and `doctorName` into it on `REMINDER_SENT`.
Rule `FE-FOR-REDACT-001` greps the executable SQL for every clinical
column name and refuses to run if one appears, so this cannot rot into a
promise in a comment. The rehearsal also plants recognisable patient,
doctor, procedure, actor and tenant names in the fixtures and then greps
the rendered reports to prove none of them come out.

---

## The eight signals

| Id | Severity | What it compares |
|---|---|---|
| `FE-FOR-001` | CRITICAL | **The named lead.** An appointment-lifecycle audit row records the **actor's** organization against the **target's** appointment. If they differ, a tenant acted on another tenant's appointment. Covers `APPOINTMENT_CREATED`, `APPOINTMENT_RESCHEDULED`, `APPOINTMENT_CANCELLED`, `REMINDER_SENT` |
| `FE-FOR-002` | HIGH | Same join, one level down: audit `projectId` against appointment `projectId`. Catches a cross-**project** breach inside one tenant, which 001 is blind to by construction |
| `FE-FOR-003` | HIGH | An audited lifecycle action whose target row is gone. Deletes write no audit row, so this is the **only** delete signal the schema supports |
| `FE-FOR-004` | CRITICAL | For **any** audit action, the organization the row was written under against the organization of the user who wrote it. Not limited to the clinic module |
| `FE-FOR-005` | CRITICAL | An appointment referencing a doctor, procedure, customer or project in a **different organization**. Persistent residue of a cross-tenant write, independent of any audit row |
| `FE-FOR-006` | CRITICAL | A doctors / procedures / tasks row whose `organizationId` disagrees with its project's organization. **This is the "a doctor could be moved into another tenant" signature**, and the `tasks.update` cross-tenant write |
| `FE-FOR-007` | MEDIUM | An appointment whose `updatedAt` is later than the newest audit row naming it — something moved the row without auditing. Triage, not evidence |
| `FE-FOR-008` | HIGH | For every audited action carrying a project, the actor should hold a membership in that project. Absence is the shape the breach leaves |

Six coverage counters (`FE-FOR-C01`…`C07`) describe the database so a
zero can be read. `C04` reports roster audit rows and `C07` reports the
session's read-only state.

---

## What a result means

### A positive result looks like

```
VERDICT: FINDINGS -- 7 critical, 7 high, 1 medium.
  FE-FOR-001  3 finding(s)  severity CRITICAL
      <appointment-uuid>  actor-org <1111...> -> <2222...>  at 2026-02-14 10:00:00
                          AUDIT_ACTOR_ORG_DIFFERS_FROM_APPOINTMENT_ORG:APPOINTMENT_RESCHEDULED
```

Two different organization ids on one row is the signature. Follow
[RESPONSE.md](RESPONSE.md) before touching anything.

### A clean result looks like

```
audit rows total 5   appointment lifecycle actions present 3
appointments carrying no audit row at all : 0 of 2
doctor/procedure lifecycle audit rows : 0
VERDICT: CLEAN WITHIN COVERAGE -- ... This is NOT an all-clear
```

`CLEAN` is only reachable when there **was** at least one
appointment-lifecycle audit row to have mismatched and at least one
clinical row for a breach to have touched. The tool refuses to say
`CLEAN` otherwise.

### A clean result does **not** rule out

These are printed on every run, on a positive result and a clean one
alike:

- **Roster writes.** Doctor and procedure create/update/delete write no
  audit row at all. No query over this schema can answer whether the
  roster was altered. Only residue (006) is visible, and only if it was
  never reverted.
- **Reads.** No read writes an audit row anywhere. Every purely-reading
  path — rosters, appointments, another tenant's patient contact details
  — is undetectable regardless of outcome.
- **Unauthenticated access.** The `@Public()` reminder routes that
  returned a patient's name, phone and doctor from a bare appointment
  UUID wrote no audit row. Nothing distinguishes "never called" from
  "called ten thousand times".
- **Reverted writes and deletions.** 005 and 006 look for residue that
  persists. A write corrected afterwards leaves nothing.
- **The blind window.** Anything before the first audit row timestamp is
  outside what the audit table can describe, whatever the outcome.
- **Intra-tenant abuse.** Phase 33 closed the clinic surface's *role*
  gap: before it, any employee of a tenant could rewrite their own
  tenant's roster. That is a legitimate org-scoped write and leaves
  **no residue anywhere** — same organization, correct tables, no audit
  row. It is not detectable at all.
- **004 and 008 need adjudication.** A user moved legitimately between
  tenants, or granted membership after the fact, produces the same shape
  as an attacker.

---

## Rehearsal

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File ops\forensics\Rehearse-ForensicScan.ps1
```

24 scenarios, each asserting an outcome. It covers the refusals, the
read-only guard **with a paired control**, the redaction guard, a seeded
clean dataset, a seeded contaminated dataset that must fire **all eight**
checks, and a zero-coverage database that must return exit 3 and not 0.

The rehearsal is itself mutation-tested. Three deliberate defects were
injected to prove it can go red:

| Injected defect | Caught by |
|---|---|
| Delete the `FE-FOR-006` check from the SQL | `R6-006` |
| Make the scanner report `CLEAN` at zero coverage | `R7a`, `R7b` |
| Remove the scanner's read-only session guard | `R3c`, `R5a`, `R6a`, `R7a` |

The third of these was **missed on the first attempt** — the rehearsal
tested that PostgreSQL honours the setting, not that this scanner ran
under it. That was a real gap in the test, and it is why the scanner now
self-verifies (`FE-FOR-C07` / `R3c`). A rehearsal that cannot go red is
not evidence.

---

## Getting a copy to scan

The tool will not touch `firstelement`, and should not be pointed at
production. Take a copy and name it `*forensic*`:

```powershell
# on the production host, into a forensics-named file
pg_dump -Fc -f firstelement_forensic.dump <db>
# restore it into a scratch cluster / a database named *_forensic_*
```

Do not copy `.env`, credentials or key material alongside it. If the
production database cannot be copied, the honest outcome is
`INCONCLUSIVE`, and that is a legitimate result to record.

---

## What would have to change for this to answer the roster

Nothing in this directory. It is a query, and a query cannot invent a
record that was never written. The doctor and procedure paths need
**schema and code** changes first:

1. **Audit the clinic roster writes.** Create, update and delete on
   `doctors` and `procedures` must write an `audit_logs` row naming the
   actor's organization and the resource id — the same shape
   `APPOINTMENT_*` already uses. Without this there is no forensic
   question to ask.
2. **Audit deletes explicitly**, recording the deleted id. Today a
   deletion leaves nothing, and 003 can only infer one for appointments
   that happen to have been created through an audited path.
3. **Audit reads of clinical data**, or route them through an access log.
   This is the only way the read half of the breach becomes answerable.
4. **Composite tenant foreign keys.** `appointments_doctorId_fkey` is a
   single-column FK on `id`; nothing stops an appointment in tenant A
   pointing at a doctor in tenant B. A `FOREIGN KEY (doctorId,
   organizationId) REFERENCES doctors(id, organizationId)` would make
   residue impossible rather than merely detectable.
5. **Record a retention/partitioning decision for `audit_logs`** so the
   trail is still there when someone finally asks.