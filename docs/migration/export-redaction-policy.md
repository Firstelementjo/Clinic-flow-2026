# Export redaction policy

**Phase 9 — `backend/reporting-exports`.** The rule this document states in one sentence:

> A column a caller may not see is **absent from the artefact**. It is never a blank cell, never a
> placeholder, and never an empty-looking value.

Everything below is that rule, why it is different from the usual redaction guidance, and where it is
enforced in code and in tests.

---

## 1. Why absence and not substitution

`FieldRedactor` in `:backend:tenant-security` offers both `redact` (substitute a constant) and `project`
(drop the column). **This module uses only the dropping form**, and the reason is specific to exports:

| A substitution produces | A reader concludes |
|---|---|
| `[redacted]` under a money header | "this period had no revenue" |
| `—` under a money header | "this period had no revenue" |
| `—` under `customer.primaryEmail` | "this customer has no email" |
| *no `customer.primaryEmail` column* | "this report carries no emails" |

Only the last one is true. Every other cell asserts a fact about the **data**, and all three are false:
they are statements about the **viewer**. ADR-0007 rule 5 forbids exactly this collapse for finance
("unpriced is not zero"), and a redaction placeholder is the same collapse in a different column.

There is a second reason, which is about information rather than comprehension:

- A placeholder is a **value**. Any constant present in some exports and absent from others leaks, per
  export, how many columns were hidden — and a policy that varied its placeholder would make that a
  covert channel.
- The number of columns in the file is visible in the header row, so "how many were hidden" is derivable
  from the file anyway. There is no additional protection to buy by hiding it, and a real cost to pay by
  printing it twice.

## 2. Where the answer is recorded instead

Because the bytes cannot carry it, the **job row** carries it. `export_jobs` holds:

- `"columns"` — the emitted keys, in the version's declared order, pinned at creation so a re-run is
  byte-comparable;
- `"redactionPolicy"` — the name of the tenant's declared policy;
- `"authorizationSnapshot"` — the emitted set, the **withheld set**, and the reason for each
  (`MISSING_PERMISSION`, `POLICY`, `NEVER_EXPOSED`), plus the requester, their role, the evaluated-at
  instant, and the version's own `requiredPermissions`.

The schema refuses an empty snapshot (`export_jobs_authorization_snapshot_check`), because a snapshot that
looks recorded but records nothing is worse than no snapshot.

So the division of labour is:

> The **artefact** answers *"what am I allowed to see?"*
> The **job row** answers *"what did this export hide, and why?"*

Both are answerable months later, without re-running anything.

## 3. The three gates, in evaluation order

`ExportRedactionResolver.reasonFor` evaluates three gates and the **first** one that fires is the reason
recorded:

1. **`FieldRedactionPolicy.NEVER_EXPOSED`** — credentials and secrets. Unconditional, for every principal
   including `ADMIN`. Cannot be configured away.
2. **The tenant's declared `ExportRedactionPolicy`** — a floor regardless of permission.
3. **`ReportField.requiredPermission()`** — the caller's effective permission set, from
   `AuthorizationService.effectivePermissions`.

Order affects only the recorded reason. A field that is both credential-class and permission-gated reports
`NEVER_EXPOSED`, because that is the answer an operator debugging a missing column needs.

### The declared policies

| Wire value | Withholds |
|---|---|
| `none` | nothing beyond the other two gates |
| `no-contact-details` | `customer.primaryEmail`, `customer.primaryPhone` |
| `no-personal-detail` | the above plus `customer.firstSeenAt`, `customer.lastSeenAt`, `conversation.firstResponseAt`, `conversation.resolvedAt` |
| `strict` | the above plus `usage.cost`, `usage.revenue`, `usage.margin`, `usage.markupRate`, `usage.rateCardVersionId` |

They are **nested**: each is a superset of the one before it, asserted by
`ExportRetentionTest`/`ExportRedactionResolverTest` so a stricter policy can never withhold less.

An undeclared policy name is a refusal at authoring time, never a default. A tenant that has declared no
class is treated as `none`, **not** as the strictest policy — because the permission gate is already the
authoritative decision and failing closed here would make an unset column hide every field for an admin.

## 4. The permission gate, and what an employee sees

`ReportField.requiredPermission()` is not configurable and is evaluated for every export.

Every `usage.*` field requires `Permission.ANALYTICS_READ_ORGANIZATION`. An `EMPLOYEE` holds
`ANALYTICS_READ_PROJECT` and **none** of those fields, so an employee's usage export resolves to an empty
emission and the job service refuses it (`EmptyExportRefusal`).

Declaring a lesser "non-money" finance field at the project level was considered and rejected. It would have
made this module a way *around* the gate rather than a projection of it: `:backend:reporting-finance`
applies `ANALYTICS_READ_ORGANIZATION` to `/usage/daily` and `/usage/monthly`, and an export path with a
weaker check than the dashboard is precisely the drift this migration exists to prevent.

`export_jobs` always has a project, because an export with no project scope would be an organisation-wide
extraction — the one thing the project predicate exists to prevent.

## 5. Re-authorization happens three times

A permission can lapse between any two of these, so all three are real:

1. **Creation** resolves the caller's permissions and pins the resulting column set.
2. **Generation** (`ExportJobService.generate` → `reauthorize`) re-resolves from the requester's *current*
   role and memberships, never from the snapshot, and **refuses** rather than producing a narrower file: a
   job whose column set shrank between queueing and generation is a file whose evidence does not describe it.
3. **Download** (`ExportDownloadService.redeem`) re-checks every **emitted** field's permission against the
   caller holding the link, and refuses if any lapsed.

### A download is refused, never re-redacted

This is the decision that is easiest to get wrong. The stored object's checksum is the evidence of what was
produced. Serving a narrower byte sequence under that checksum would make the evidence untrue, and two
downloads of the same job would differ with no explanation. So the download path **refuses**, and records
the refusal with the field and the permission that lapsed.

`ResolvedExportColumns.satisfiedBy` / `firstUnmetField` answer against the **emitted** set, not the
caller's own column set — resolving the caller's set instead would answer "yes" for a caller who cannot see
half the file.

## 6. DOCX templates cannot reach a withheld field

A template placeholder is `{{report.<key>}}`, `{{meta.<name>}}` or `{{totals.<key>}}`.

`DocxTemplateRenderer.extractPlaceholders` validates **every** placeholder against the report version's
*emitted* set before a single byte is written. A key in the registry but withheld from this caller is
refused with `DOCX_UNAPPROVED_FIELD` — that is the redaction bypass the check exists to close, and it is
why the check is against the emitted set rather than the registry.

Validation happens before any write, so a refused template never leaves a half-rendered document in the
sink. A partial document that gets stored is worse than no document: an operator would open a truncated
report believing it is the export.

## 7. Bounded amounts, closed values, and what is *not* in a file

- **No credential class column is selectable.** No `ReportField` expression references `passwordHash`,
  `tokenHash`, `credentials`, `secretBox`, `apiKey`, `encryptionKey` or `privateKey`; asserted by
  `ExportQueryPlannerTest.noCredentialFieldExists`.
- **No raw SQL, arbitrary column, arbitrary sort, or arbitrary join.** The only vocabulary is the closed
  `ReportField` / `ReportFilterField` / `ReportSubject` enums, whose SQL is a compile-time constant on each
  constant. No method anywhere accepts a table name, column name, predicate fragment, sort expression or
  `WHERE` clause.
- **No secrets.** The download signing key appears in no projection, no audit metadata and no log line. The
  download event records the signature **id** and its expiry — never the URL, because a URL in a database
  row is a bearer credential with an expiry nobody manages.
- **No storage key in the URL.** The token carries the job id, the tenant, the expiry and a random signature
  id. Nothing else. The key is resolved from the row *after* authorizing.
- **A file name carries no customer data.** `export-<jobId>.<format>`, derived from the id and the format
  only, because a file name is the one part of an export that leaves the system.

## 8. What a refused export looks like, rather than an empty one

| Situation | Response |
|---|---|
| Every declared column withheld | `EmptyExportRefusal` → 403. A file whose header row is empty cannot be told apart from a genuinely empty report. |
| A filter that cannot be pushed | `FILTER_NOT_PUSHABLE` → 422, naming the key. A post-filter runs *after* aggregation, so the number that comes out is not the number asked for. |
| A sort naming a column that is not emitted | `SORT_NOT_PROJECTABLE` → 422. A sort on a withheld column is an ordering oracle over data the caller may not see. |
| A template naming a withheld field | `DOCX_UNAPPROVED_FIELD` → the job fails, nothing is stored. |
| Row ceiling exceeded | `EXPORT_ROW_CAP_EXCEEDED` → the job fails. A silently truncated financial extract is worse than no extract. |
| A permission revoked before download | `authorization-revoked` → 403, refusal recorded on the download event. |

## 9. Where each claim is tested

| Claim | Suite |
|---|---|
| A withheld column is a set difference, never a value | `ExportRedactionResolverTest` |
| Employee sees no usage columns at all | `ExportRedactionResolverTest`, `ExportQueryPlannerTest` |
| Policy beats permission, and policies are nested | `ExportRedactionResolverTest` |
| `NEVER_EXPOSED` outranks a permission gate | `ExportRedactionResolverTest` |
| Download refuses rather than re-redacts | `ExportRedactionResolverTest` (mechanism), `ExportJobLifecycleIntegrationTest` (end to end) |
| A template cannot reach a withheld field | `DocxTemplateRendererTest` |
| No credential column is selectable | `ExportQueryPlannerTest` |
| The token carries no locator | `ExportSignatureServiceTest` |
| Every deletion event carries `legalHoldChecked` | `ExportRetentionTest`, `ExportJobLifecycleIntegrationTest` |
