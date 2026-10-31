# Clinic kit Phase 3 — Patient management

Status: **IN PROGRESS — gate not met.**

Date: 2026-10-13
Branch: `migration/spring-angular`
Build contract: `docs/clinic/answers.md`
Kit phase source: `firstelement-dental-clinic-feature-kit/plan.md` §6 (Phase 3)

---

## What this phase is

The kit's Phase 3 objective is "unified patient profile, search, duplicate warning, protected
medical summary, attachments, and CRM linkage". Before this work there was no patient at all: a
patient's name, phone and email were free-text columns on `appointments`, and
`appointments.customerId` pointed at the CRM `customers` table that DEC-015 says is *not* a
patient. DEC-015 was true in a document and false in the schema.

This report covers the portion of Phase 3 that the 2026-10-13 answers unblocked. It does **not**
claim the phase complete.

---

## Delivered

### 1. `patients` schema — committed as `1efeeae`

`ops/flyway/sql/V20261013000000__clinic_patients.sql` and its byte-identical copy in
`backend/database/src/main/resources/db/migration/`.

Per DEC-016 Option C: `patients`, `patient_identities`, `patient_attachments` are new.
`procedures` stays the service catalogue; `doctor_profiles` is dropped because `doctors` is
already the profile.

- `primaryPhone` unique **per tenant**, not globally.
- `customerId` optional and composite, so a walk-in with no CRM conversation is the normal case and
  cannot be linked to another tenant's contact.
- DEC-007: `jurisdiction`, `retentionClass`, `retentionExpiresAt` exist, **nullable and inert** —
  no purge job, no deletion behaviour. The one non-inert rule is `legalHold` blocking `deletedAt`,
  expressed as a CHECK constraint so it is a database invariant rather than a service convention.

This migration took the chain to **28 migrations and 105 tables**. The current chain, after the odontogram and its vocabulary project-scope correction,
measures: **30 migrations, 107 tables, 238 foreign keys, 107 primary keys,
436 CHECK constraints, 62 enum types, 228 tenant-led indexes.**

Every figure is a measured census, and each is counted per constraint type rather than as one sum
— "constraints" in this chain has no single number.

Two earlier versions of this line were wrong and are corrected here. One read "234 constraints,
433 indexes": those were the *foreign-key* and *CHECK* censuses, not totals. The other quoted the
current counts while labelling them as the state after the patients migration alone.

Three defects the chain caught while writing it, all recorded in the commit:

- the composite FK needed a unique index on exactly `("id","organizationId")`; the 3-column index
  written first does not satisfy it;
- an `updatedAt` trigger function was invented and then **removed** — no table in this chain has
  one, the 18 existing triggers are append-only/period guards, and the app sets `updatedAt` in SQL;
- the migration's own post-condition caught a real design bug: `patientId` was `UNIQUE` on
  `patient_identities`, which would have allowed a patient exactly one identity. It is now a plain
  tenant-led index, with uniqueness on the identity value and on "one primary per patient".

### 2. `PatientRepository`, `PatientService`, `PatientModels`

`backend/crm-core/src/main/java/com/firstelement/crm/clinic/patient/`

Every statement is bound by the existing `ClinicScope`, obtained through one of its three checked
construction paths. There is no method on the repository that accepts an unchecked
`organizationId`. Soft-deleted rows are filtered **in SQL**, not in Java. Search never touches
retention columns and never joins `customers` — a patient search that could reach CRM contact data
would blur the DEC-015 boundary.

This is the **first write in the clinic module**; `ClinicRepository` had none by design. The
tenant scoping comes from the same `ClinicScope` the read paths use, so a write cannot be scoped
more loosely than a read. Insert requires a project-scoped scope and refuses otherwise: a patient
belongs to exactly one clinic, and writing "the first accessible project" would file a record under
a clinic the patient does not belong to. The refusal is `IllegalStateException`, not
`PolicyDeniedException` — it is a caller mistake, not an authorization outcome, and rendering it as
a 403 would tell the operator a false thing.

Duplicate scoring is fixed in code, not configurable at runtime:

| signal | score |
| --- | --- |
| phone | 60 |
| date of birth | 15 |
| family name | 10 |
| given name | 10 |
| family + given + DOB together | +20 bonus |

Reporting floor 40. A shared surname alone scores 10 and is therefore never reported.

### 3. No auto-merge — enforced structurally

`PatientService` has **no merge, link, attach, deduplicate, consolidate or auto-merge method at
all**. The kit says "do not auto-merge on weak signals". A merge operation that exists but is gated
on a threshold can be gated wrongly, called directly, or have its threshold lowered later, and two
patients merged by accident is not recoverable. The capability is absent rather than restricted.

`PatientServiceIntegrationTest` asserts this reflectively over `PatientService.class.getMethods()`,
so adding a merge method later turns the build red rather than passing review.

---

## Tests

`backend/crm-core/src/test/java/com/firstelement/crm/clinic/patient/PatientServiceIntegrationTest.java`
— 9 tests, all green, against real PostgreSQL.

```
cmd.exe /c ".git\t-patient.cmd"
```

| test | asserts |
| --- | --- |
| no merge, link or deduplicate operation exists | structural; the kit's "do not auto-merge" |
| shared surname alone is not reported; a matching phone is | weighting; a surname match does not surface |
| a patient is never its own duplicate candidate | excludes by id |
| the same phone is refused twice in one clinic | DEC-016 per-tenant uniqueness, at the database |
| the same phone is allowed in a different tenant | not a global unique index |
| a patient is invisible to another organization | `find`, `list` and `search` all tenant-isolated |
| a soft-deleted patient is not returned | `deletedAt` filtered in SQL |
| a patient under legal hold cannot be soft-deleted | DEC-007 legal hold as a CHECK, not a service rule |
| creating a patient without naming a clinic is refused | no silent default project |

**Load-bearing proof.** A `mergePatientsProbe` method was temporarily added to `PatientService`;
the structural test turned **FAILED**. The probe was removed and the suite returned **BUILD
SUCCESSFUL**. The structural guarantee is enforced, not merely asserted in prose.

### Three defects this work hit, and what they were

- `selectp."id"` — Java text blocks strip the trailing newline of the incidental closing line, so
  `""" + COLUMNS` glued `select` to the first column. Trailing **spaces** are stripped too. Fixed
  with the `\s` escape at every concatenation point.
- `column "locale" is of type "Locale" but expression is of type character varying` — `locale` is an
  enum. Fixed with `cast(:locale as "Locale")`.
- `could not determine data type of parameter $7` — `(:param IS NOT NULL AND col = :param)` gives
  PostgreSQL no type context for the first occurrence. The null guards were also redundant:
  `column = NULL` is NULL, not true, so the OR correctly does not fire. Removed in favour of
  `cast(:param as ...)` on the comparison only.

Each was found by running the test, not by reading it.

---

## Gate status — NOT met

The kit's Phase 3 gate reads:

> Patient create/search/duplicate/authorized-detail flows pass in Arabic and English; unauthorized
> roles cannot enumerate.

| gate clause | status |
| --- | --- |
| create / search / duplicate flows | **met** at the service and data layer |
| unauthorized roles cannot enumerate | **partially met** — tenant isolation is proven; HTTP-level role authorization is not, because there is no controller yet |
| Arabic and English flows | **not met** — no Angular surface exists for patients |

Phase 3 is not complete and must not be reported as such.

## Remaining in this phase

1. `PatientController` under `/api/v1/projects/{projectId}/patients`, with HTTP-level authorization
   tests asserting that a caller lacking the clinic permission gets the same answer for "absent" as
   for "not authorized".
2. Arabic-first patient list and detail in Angular, with the i18n keys this phase introduces.
3. Timeline projections (kit item 4): appointments, encounters, treatments, communications,
   attachments against a patient.
4. Protected medical summary, allergies, medications, consents — depends on Phase 7/8 tables and on
   DEC-009/DEC-010 vocabulary.
5. Audit and source-of-change on patient writes (kit item 6) — not yet instrumented.

## Open decisions still blocking parts of this phase

- **DEC-007 retention values** — lawyer. Columns are nullable and inert; they stay that way until
  the numbers exist. Nothing in this phase needs them.
- **DEC-009 odontogram finding vocabulary** — clinical lead. Blocks the clinical summary only.
- **DEC-016 customer linkage** — the link is optional and unidirectional in this phase; the kit's
  "CRM linkage" bidirectional sync is not built.

## Explicitly not done in this phase

- **Clinic billing.** DEC-004 deferred it; `patients` adds no invoice, payment or balance table.
- **Phase 12 decommission.** Unchanged, still requires two observation windows and four human
  sign-offs.