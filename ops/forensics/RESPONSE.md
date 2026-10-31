# Response to a positive forensic result

A detection nobody acts on is a missed incident. This is the order to do
things in, written for the person who is holding the report at 02:00 and
does not have time to read all of it.

**This document contains no patient data and never should.** Work from
the resource ids in the report.

---

## 0. Before you touch anything

**Do not remediate first.** A fix applied before the database copy and
logs are preserved destroys the evidence that makes the rest of this
possible, and you will not get a second look at this window.

If you have not already, take **two** copies now:

- a **database** copy (`pg_dump -Fc`), named `*forensic*`
- a copy of **application logs, reverse-proxy access logs and any
  infrastructure logs** covering the audit window printed in the report

Preserve them somewhere the compromised application cannot reach. Writing
the dump to a directory the running service can write is not preservation.

## 1. Scope it

From the report, take:

- the **actor organization ids** (`organization_a`)
- the **target organization ids** (`organization_b`)
- the **actor user ids**, via `FE-FOR-004` / `FE-FOR-008` findings whose
  `resource_id` is an `audit_logs.id`
- the **appointment / roster row ids**
- the **timestamp range** of the findings

Read `FE-FOR-004` and `FE-FOR-008` findings first. They are the ones that
put a *person* at the centre of it, and they are also the ones with the
most innocent explanations — check whether that user was legitimately
moved between tenants or granted membership after the fact before you
conclude anything.

Then establish the **earliest** and **latest** finding timestamp. That
window is what you will have to explain to a regulator, and the report
gives it to you precisely.

## 2. Contain

- **Revoke every session** for the identified actor users. Not "sign them
  out" — revoke. If the session store is Redis, deleting the key is not
  enough if the token is stateless; rotate the signing secret as well.
- **Reset credentials** for the actor users and for every service
  principal in the same tenant. Assume any credential that touched the
  compromised host during the window is exposed.
- **Rotate the AI provider keys** if the actor could reach the clinic AI
  simulator. Model calls may carry patient context.
- If the actor organization is one you do not control, the finding is a
  report *about* another party, not just an internal incident — escalate
  to whoever owns that tenant's relationship before you touch their data.

## 3. Preserve

- Keep the database copy **read-only** and record its checksum and the
  time it was taken.
- Keep the forensic report JSON itself. It is the artefact that records
  what was and was not looked at — which is what you will need if
  someone later asks *why you didn't find this sooner*.
- Record who ran the scan, when, and against which copy.

## 4. Assess the patient-data impact

Only `FE-FOR-001`/`003` (appointment lifecycle) and `FE-FOR-005`/`006`
(persistent residue) involve clinical rows that can still be enumerated.

- For each affected appointment id, establish the **tenant** and the
  **date range**, not the patient's identity. A count of appointments
  touched, per tenant, per date, is what a notification obligation is
  measured in.
- `FE-FOR-005`/`006` findings tell you about **state**, not about **who
  read what**. Do not let a residue finding be reported as proof of
  disclosure.
- Remember the standing limit: **the read paths are invisible.** If the
  actor could read, this tool cannot tell you that they did. If the
  breach's read capability matters to your assessment — and for patient
  data it usually does — you must assume exposure of whatever was
  readable in the window unless you have independent evidence otherwise.

## 5. Tell people

- **Security / incident response owner** — immediately.
- **Data protection officer** — the clock on notification obligations runs
  from awareness, not from the fix. A forensic result with findings *is*
  awareness.
- **The affected tenants** — through the contractual channel, with
  counts and dates, not with patient records.
- **The legacy runtime owner** — the breach is described as fixed, but a
  fix that is not verified against a restored copy of the affected
  window is an assumption.

## 6. Close the loop

The finding is only useful if the same question is answerable next time.
From the incident write-up, the concrete asks are in the README's
"What would have to change" section: audit the roster writes, audit
deletes, audit reads of clinical data, and add composite tenant foreign
keys so cross-tenant residue becomes impossible rather than merely
detectable.

---

## If the result was `INCONCLUSIVE`

That is not an all-clear, and it should not be recorded as one. Record
it as **"not answerable from the available audit data"**, with the
coverage figures that show why. The remediation asks above do not change:
they are what make the next incident answerable.