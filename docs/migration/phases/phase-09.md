# Phase 9 — Reporting and CSV/DOCX export

**Status:** IMPLEMENTED — **179 tests, 0 failures, 0 skipped**, including the
Testcontainers integration suite that runs the real Flyway chain
(`ExportJobLifecycleIntegrationTest`, 31 tests) against two tenants and a throwaway
pgvector container. The acceptance gate is met.
**Owner agent:** `firstelement-exports`
**Module path:** `backend/reporting-exports/**` (the task file's `backend/exports/**`
path was not used; this repository's convention is `backend/<domain>/`)
**Companion document:** `docs/migration/export-redaction-policy.md`
**Depends on:** `V20261005060000__report_definitions`,
`V20261005070000__export_jobs`; the frozen tenant/row/field policy in
`backend/tenant-security/**`; ADR 0007 §2 (authorization applied before aggregation).

## Objective

Auditable, permission-aware data delivery that never blocks a request thread and
never leaks an unauthorized row or field.

## What is built

### Versioned catalogue

`report_definitions` (mutable: labels, version pointers) and `report_versions`
(**immutable**: dimensions, measures, filters, default sort, columns, denominator
rules, metric semantics). A definition can be renamed freely; editing a body
creates a version, which is what makes a pinned export reproducible after the
report has changed ten times.

Every declared key is resolved through the typed registry **before** the write, so
the database never holds a version the module would refuse. A document template
catalogue mirrors the shape; a template version is configuration and is classified
`never purge` by ADR 0009 §4.

### The closed vocabulary

Three enums and no fourth:

- `ReportSubject` — `CUSTOMERS`, `CONVERSATIONS`, `USAGE_LEDGER`, each with one
  static `FROM`/`JOIN` body.
- `ReportField` — every selectable expression, its required `Permission`, its role
  (dimension/measure), its kind, and **both** Arabic and English labels.
- `ReportFilterField` — every pushable predicate, each naming **its own** operator.

The SQL is a compile-time constant on each constant. No method accepts a table
name, column name, predicate fragment, sort expression or `WHERE` clause, so there
is no parameter a caller could use to reach the statement text. This is the
difference between "a versioned metadata registry" and "an arbitrary query surface",
and it is enforced by the types rather than by review.

### Policy before aggregation

`ExportQueryPlanner` assembles the statement with the tenant predicate already in
it:

- the predicate is written by `ScopedWhere`, not by the planner, so it cannot be
  forgotten, cannot be placed after the `GROUP BY`, and cannot be shadowed — the
  tenant parameters bind **first**;
- field authorization happens before the select list exists, so an unauthorized
  expression was never in the statement;
- `GROUP BY` and `ORDER BY` use **output ordinals**, and only over emitted columns.

A filter that cannot be pushed raises `FilterNotPushableException` and **no
statement is produced**. A post-filter runs after aggregation, so the number that
comes out is not the number the caller asked for.

### Formats

- **CSV**: streaming, UTF-8, RFC 4180, stable column order, BOM configurable
  (default on, documented below). Machine-style values (ISO dates, plain decimals)
  with a locale-chosen header row.
- **DOCX**: Apache POI XWPF 5.4.1, rebuilt from a controlled template. `w:bidi` on
  the paragraph **and** `w:rtl` on every run — both, because either alone looks
  almost right and is subtly wrong. Bilingual labels, localized dates/numbers,
  currency as its own column, tables, totals, page breaks.

### Lifecycle

`queued → running → completed | failed | cancelled → (retry) → queued`, plus
`expired` and `deleted`. Every transition is a guarded `UPDATE` whose guard is the
expected state **and** version, so an illegal or racing transition loses on a zero
affected-row count rather than on application ordering.

Persist first, send second: the job row and the outbox intent are written in one
transaction, the request returns a `QUEUED` row, and generation happens only in
`ExportJobHandler`.

### Download

HMAC-SHA256 signed, short-lived, tenant-bound links. The token carries the job id,
the tenant, the expiry and a random signature id — **not** the storage key, the
checksum or the file name. The object is re-hashed at redemption and compared with
the recorded checksum. Every attempt, granted or refused, is written to
`export_download_events`.

### Retention

Expiry is **enabled** and per-tenant; purge is **disabled in every environment** and
refused at startup. ADR-0009 §4 rule 4 forbids purging any table whose
retention-class column is not populated, and that is a per-tenant data question a
property flip is not how an operator answers it. Legal-hold state is queried before
every deletion and the answer is recorded on every event. The object is deleted
**before** the row says so.

## Hard rules — where each is enforced

| # | Rule | Enforcement | Test |
|---|---|---|---|
| 1 | Persist first, send second | `@Transactional create` writes the row and publishes the outbox intent; only `ExportJobHandler` generates | `ExportJobLifecycleIntegrationTest` |
| 2 | Reauthorize at creation and download (and again at generation) | `ExportJobService.reauthorize`, `ExportDownloadService.reauthorizeOrRecordDenial` | `ExportRedactionResolverTest`, integration suite |
| 3 | No secrets, no unauthorized fields, no public object paths | opaque keys, token without a locator, signing key never logged | `ExportSignatureServiceTest` |
| 4 | Arabic-safe CSV, documented BOM | `CsvExportWriter`; `firstelement.exports.generation.csv-bom` | `CsvExportWriterTest` |
| 5 | Bounded DOCX field vocabulary | `DocxTemplateRenderer.extractPlaceholders` against the **emitted** set | `DocxTemplateRendererTest` |
| 6 | Policy before aggregation; unpushable filter ⇒ refusal | `ExportQueryPlanner`, `FilterNotPushableException` | `ExportQueryPlannerTest` |
| 7 | Cross-project attempts return nothing and create a security event | `TenantQueryGuard` (security module) + non-disclosing `PolicyDeniedException` | `ExportQueryPlannerTest`, integration suite |

## Acceptance gate — current status

| # | Gate | Status |
|---|---|---|
| 1 | Unauthorized rows/fields never appear in CSV or DOCX, proven by grepping the bytes | **MET** — `ExportRedactionResolverTest`, `DocxTemplateRendererTest`, and the integration suite's byte-level assertions (a withheld email is absent from the file *and* the row has three cells, not four with a gap) |
| 2 | Large exports do not block HTTP; memory stays bounded (measured) | **MET** — 20,000 rows, every one accounted for against the table, under a 96 MB heap allowance while the file exceeds 500 KB |
| 3 | Expired links fail; signed URLs are tenant-scoped; a cross-tenant URL fails | **MET** — `ExportSignatureServiceTest` (27 tests) and end-to-end against a live signature |
| 4 | Download and deletion events are visible to an operator | **MET** — the integration suite reads them back after each refusal, and `deletionLeavesEvidence` reads both deletion outcomes plus `legalHoldChecked` on every row |
| 5 | Template referencing an unapproved field fails loudly | **MET** — `DocxTemplateRendererTest` |

The seven states of the job machine are each exercised end to end against the real
schema: `QUEUED` (creation returns a row and no bytes), `RUNNING` (a claim), `COMPLETED`
(checksum re-hashed from the stored object), `FAILED`, `CANCELLED` (from a RUNNING job,
then retried), `EXPIRED` (object deleted, then the state), `DELETED` (tombstone, row
retained).

## Test commands

```powershell
.\gradlew.bat :backend:reporting-exports:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Targeted runs used while developing:

```powershell
.\gradlew.bat :backend:reporting-exports:test --rerun-tasks --no-build-cache --no-daemon --console=plain --tests "com.firstelement.exports.ExportQueryPlannerTest"
.\gradlew.bat :backend:reporting-exports:test --rerun-tasks --no-build-cache --no-daemon --console=plain --tests "com.firstelement.exports.ExportRedactionResolverTest"
.\gradlew.bat :backend:reporting-exports:test --rerun-tasks --no-build-cache --no-daemon --console=plain --tests "com.firstelement.exports.DocxTemplateRendererTest"
.\gradlew.bat :backend:reporting-exports:test --rerun-tasks --no-build-cache --no-daemon --console=plain --tests "com.firstelement.exports.ExportSignatureServiceTest"
.\gradlew.bat :backend:reporting-exports:test --rerun-tasks --no-build-cache --no-daemon --console=plain --tests "com.firstelement.exports.ExportRetentionTest"
.\gradlew.bat :backend:reporting-exports:test --rerun-tasks --no-build-cache --no-daemon --console=plain --tests "com.firstelement.exports.ExportJobLifecycleIntegrationTest"
```

The integration suites need a reachable Docker daemon; `build.gradle.kts` forwards
`DOCKER_HOST`, `DOCKER_TLS_VERIFY`, `DOCKER_CERT_PATH` and
`TESTCONTAINERS_RYUK_DISABLED`. Testcontainers is pinned to **1.21.4** — unpinned,
Gradle resolves 1.21.3, whose docker-java cannot negotiate an API version with
Docker Desktop 29.x on this host.

### Per-suite counts

Read from `backend/reporting-exports/build/test-results/test/TEST-*.xml`, which is
authoritative; Gradle's console summary counts a nested class as part of its parent
and the two therefore disagree.

| Suite | Tests | Stage | Notes |
|---|---|---|---|
| `ExportQueryPlannerTest` | 32 | `backend-unit` | 5 nested: policy-before-aggregation, filter pushdown, finance subject, stable metadata, closed vocabulary |
| `ExportSignatureServiceTest` | 27 | `backend-unit` | 7 nested: round trip, tamper, expiry, tenant binding, no locator, checksum, signing key |
| `DocxTemplateRendererTest` | 25 | `backend-unit` | 5 nested, incl. RTL asserted at section and paragraph level |
| `CsvExportWriterTest` | 23 | `backend-unit` | 5 nested: encoding/BOM, RFC 4180 quoting, structure, values, streaming |
| `ExportRedactionResolverTest` | 23 | `backend-unit` | 5 nested: policy gate, permission gate, recorded evidence, download re-authorization, never-exposed |
| `ExportRetentionTest` | 18 | `backend-unit` | 3 nested: window, confirmed deletion, purge-disabled |
| `ExportJobLifecycleIntegrationTest` | **31** | `backend-integration` | 7 nested: happy path, tenant isolation, finance gate, download, cancellation/retry, deletion, bounded memory |
| **Total** | **179** | | **0 failed, 0 skipped** |

The integration suite is the only one that needs Docker (`postgresql`, `pgvector`); the
other six are pure JVM and run in any environment.

Every suite has a non-zero floor. There is no skipped, disabled or `@Disabled` test in
this module, so no suite is being counted as a pass by not running.

## The eighteen defects this phase found, and the pattern in them

Every defect below compiled. Every one was fatal at run time. **None was findable by
reading the code**, and nine of the eighteen were invisible to the pure-JVM suites —
they needed a real cursor, a real ZIP, a real connection or a real transaction.

That is the lesson worth carrying to the next phase: a green unit suite is a
statement about the properties of the code, not about whether the code runs.

### The five fixed by the orchestrator (1898ed4)

1. **`ExportRowStream.Row` held the `ResultSet` instead of the values.**
   `nextBatch()` advances with `resultSet.next()`, so every row but the last of a page
   was read from an already-advanced forward-only cursor. PostgreSQL said exactly
   that: *ResultSet not positioned properly*. `Row` is now `record Row(List<Object>)`
   materialised while the cursor is on the row.
2. **`unwrap` stripped only `LazyConnectionDataSourceProxy`.** It now also strips
   `TransactionAwareDataSourceProxy`; either one returns the caller's
   transaction-bound connection, and the read's own `rollback()` then undid the job's
   own claim and terminal state.
3. **`ExportJobService.generate` was `@Transactional`.** Its streaming read joins that
   transaction, so any failure marked it rollback-only and
   `UnexpectedRollbackException` escaped the catch blocks *after* they had recorded a
   terminal state. Removed, with the reasoning in the javadoc.
4. **`ExportFilter.coerce` bound `GTE`/`LTE` date filters as `String`.** PostgreSQL has
   no `date <= character varying`, so every ranged usage filter failed with
   `operator does not exist`. Now parsed to `LocalDate`, refusing a malformed date
   with the offending key.
5. **`readCurrentRow` used `List.of`, which rejects null elements.** A SQL NULL is
   legitimate (unpriced revenue, no first-seen date), so every export with a null
   column died in a message-less NPE. Now `Arrays.asList`.

Two supporting changes kept: `diagnosticMessage` appends the root cause to the
recorded message, because a wrapper sentence alone is not diagnosable and is how five
defects hid behind one uninformative line; and `recordAttempt` no longer throws on a
FAILED attempt with a null message, because losing the failure record over a missing
sentence replaced the original fault with a second one and left jobs stuck in RUNNING.

### The nine found by writing this module's suites

6. **`ExportGenerationRunner` had no stereotype.** Every collaborator was annotated and
   this one was not, so it was never created and no export could run. Only a Spring
   context notices.
7. **`ExportProperties.signatureTtl` carried `@NotBlank` on a `Duration`.** No
   validator exists for a non-`CharSequence`, so the application context **failed to
   start**. Now `@NotNull`.
8. **`ExportRowStream.BatchIterator.hasNext()` discarded the page it read.** A paged
   read cannot answer `hasNext()` without reading the next page, so it must keep it.
9. **`ExportJobRepository`'s statements were `select """ + COLUMNS`.** Java text blocks
   strip trailing whitespace per line, so this produced `selectj."id"`.
10. **`ReportDefinitionService` wrote the wrong sort shape.** The migration requires
    `[{"field":…,"direction":"asc|desc"}]`; the service wrote `["key:asc"]`, so
    **every publication violated a CHECK**. New `ReportSortShape` owns both
    directions of the conversion.
11. **`ExportJobRepository.cancel` could not represent cancelling a queued job.**
    `export_jobs_started_check` (a biconditional) and `finished_present_check` are
    jointly unsatisfiable without filling `startedAt`.
12. **`recordAppliedFilters` recorded progress and discarded the filters** it was named
    for, while its own comment claimed the job row answered "what did this export
    apply".
13. **`DocxTemplateRenderer` had five defects**, all found by the RTL suite: NPEs on
    `getPPr()` and `getRPr()` (so **no Arabic export had ever rendered**); the
    approved-field gate read the DOCX as raw bytes when a DOCX is a **ZIP**, so it
    found no placeholders and passed everything; a one-placeholder paragraph rendered
    its braces literally; the generated table carried the body-marker row as literal
    `{{report.…}}`; and the marker row was read at `headerRows - 1` instead of
    `headerRows`, so **every generated body row was empty**.
14. **`GenerationOutcome.failed(code, message)` discarded `message`.** A caller holding
    only the outcome had a code and no explanation.

### The four found by making the integration suite green (this pass)

15. **The attempt history could only ever record `STARTED`.**
    `export_job_attempts_job_attempt_key` is a UNIQUE index on
    `(organizationId, exportJobId, attemptNumber)`: one attempt number, one row. A
    STARTED row therefore collided with the terminal outcome written moments later, and
    `recordAttempt` swallows that `DuplicateKeyException` as a duplicate-suppression win
    — so **no attempt ever reported its outcome, its duration or its row count**, which
    is the entire reason the table exists. The table's own CHECKs settle which row was
    intended (`rowsProduced` required on SUCCEEDED, an error required on FAILED,
    `finishedAt` iff `durationMs`), and all three describe a **terminal** row. Fixed by
    writing one row per attempt, carrying the terminal outcome.

16. **Every recorded download refusal was rolled back.** `mint` and `redeem` were
    `@Transactional`; each refusal path writes a DENIED row and then throws, and the
    throw took the row with it. The integration suite found two empty download
    histories where two refusals had certainly happened. Removed `@Transactional` from
    both: every write is a single guarded statement whose own `WHERE` decides whether it
    applies, and the GRANTED path is not weakened because `record` raises
    `DownloadAuditFailedException` when the audit row cannot be written.

17. **A policy-withheld column made every generation fail.** `reauthorize` compared the
    re-resolved emission against `pinnedFields` — the full declaration — instead of
    against what the job was **created with**. A tenant on `no-contact-details` creates
    a four-column report that the job pins as three, so three-against-four reported a
    permission change that never happened, and **every** generation of the one report
    shape whose purpose is to be produced with a column missing failed with
    `AUTHORIZATION_REVOKED`. Fixed by anchoring the comparison to
    `export_jobs."columns"`. A genuine downgrade still shrinks the emission and is still
    refused; nothing was weakened.

Plus a fourth, which the integration suite's own shape exposed and which is worth
naming because it was silently losing security-relevant rows:

18. **A denial recorded after a successful signature verification was rejected by the
    schema and then swallowed.** Every redemption-path refusal passed the presented
    signature id onto the DENIED row, but
    `export_download_events_signature_check` is a biconditional — GRANTED **iff** a
    signature id and expiry are present, and it is that way on purpose, because a DENIED
    row carrying a live signature would mean the refusal was recorded after the fact.
    The insert failed; `record` deliberately swallows an insert failure on a refusal
    path so that a broken audit cannot turn a correct refusal into a 500; so the refusal
    was lost. A deleted object, a replaced object and a revoked permission all recorded
    a download event with **no deny reason at all**. Refusals now record neither field,
    which is what the constraint requires; the evidence that survives is the job id, the
    actor and the deny reason, which is what an operator reads.

1. **`ExportGenerationRunner` had no stereotype.** Every collaborator was annotated
   and this one was not, so it was never created and no export could run. Only a
   Spring context notices — no unit test can prove a bean exists.
2. **`ExportProperties.signatureTtl` carried `@NotBlank` on a `Duration`.** No
   validator exists for a non-`CharSequence`, so the application context **failed
   to start**. Now `@NotNull`.
3. **`ExportRowStream.BatchIterator.hasNext()` discarded the page it read.** A
   paged read cannot answer `hasNext()` without reading the next page, so it must
   keep it; the stateless shape silently threw away every page and the loop died on
   the second call. A one-page lookahead now exists.
4. **`ExportJobRepository`'s statements were built as `select """ + COLUMNS`.** Java
   text blocks strip trailing whitespace per line, so this produced `selectj."id"`.
   `compileJava` cannot see a missing space; the database can.
5. **`ReportDefinitionService` wrote the wrong sort shape.** The migration requires
   `[{"field":…,"direction":"asc|desc"}]`; the service wrote `["key:asc"]`, so
   **every publication violated a CHECK**. New `ReportSortShape` owns both
   directions of the conversion.
6. **`ExportJobRepository.cancel` could not represent cancelling a queued job.**
   `export_jobs_started_check` (a biconditional) and `finished_present_check` are
   jointly unsatisfiable without filling `startedAt`; see the method's javadoc for
   what the schema means by it.
7. **`recordAppliedFilters` recorded progress and discarded the filters** it was
   named for, while its own comment claimed the job row answered "what did this
   export apply". Now a real `UPDATE` that preserves `__requested` and writes
   `__applied`, guarded on `RUNNING`.
8. **`DocxTemplateRenderer` had five defects**, all found by the RTL suite:
   a `NullPointerException` on `getPPr()` and on `getRPr()` (both null on a
   freshly-created element, so **no Arabic export had ever rendered**); the
   approved-field gate read the DOCX as raw bytes when a DOCX is a **ZIP**, so it
   found no placeholders and passed everything; a paragraph that was exactly one
   placeholder rendered its braces literally; the generated table carried the body
   marker row as literal `{{report.…}}` text; and the marker row was read at
   `headerRows - 1` (the header) instead of `headerRows`, so **every generated body
   row was empty**.
9. **`GenerationOutcome.failed(code, message)` discarded `message`.** The record's
   contract is that a failure is "a state with a reason an operator can read"; a
   caller holding only the outcome had a code and no explanation.

## Deliberate design decisions worth reviewing

- **`MAX_FILTERS` was lowered from 20 to 12.** At 20 the budget exceeded the whole
  declared vocabulary (17 keys), so it could never bind — a limit no caller can
  reach is decoration. The bound is now enforced on each incoming list *and* on the
  merged result, so a request of thousands of duplicate keys is refused too.
- **Every `usage.*` field requires `ANALYTICS_READ_ORGANIZATION`.** An employee
  therefore sees **no** usage columns, not "usage columns without money". Declaring
  a lesser finance field at the project level would have made exports a way around
  the dashboard's own gate. See the redaction policy §4.
- **A download is refused, never re-redacted.** The checksum is the evidence of what
  was produced; serving narrower bytes under it makes the evidence untrue.
- **`FilesystemExportObjectStore.delete` is idempotent**, returning whether the
  object is confirmed **absent afterwards**, not whether this call removed it. What
  it must never return `true` for is a file still on disk.
- **Authoring requires `Permission.WORKFLOW_MANAGE`** as a named proxy, because
  `Permission` belongs to `:backend:tenant-security` and this module does not own
  it. A dedicated `REPORT_MANAGE` is owed — see the hook requests in the Phase 9
  report.

## Runbook

### Enabling artefact purge (disabled in every environment)

Not a property flip. ADR-0009 §4 rule 3 ships purge disabled and rule 4 refuses to
purge any table whose retention-class column is not populated.

1. For every tenant, confirm `retention_classes` has a row for stream `ADMIN`, class
   `EXPORT_ARTEFACTS`, with the intended `retentionDays`. An
   `UNDECLARED_RETENTION_CLASS` row is **not** purgeable.
2. Confirm a legal hold can be raised and blocks a deletion — this is the control
   that makes the purge safe, so prove it before relying on it.
3. Rehearse on a restored copy: run `expireDue`, then read `export_deletion_events`
   and confirm every event has `legalHoldChecked = true`.
4. Change `firstelement.exports.retention.purge-enabled` in a deployment, with the
   rehearsal evidence attached. `ExportProperties.validateFor` refuses it at startup
   until that step is taken deliberately.

### Re-running a failed export

`POST /api/v1/exports/{id}/retry` requeues **the same pinned report version** — a
retry is the same export, not a new one against whatever the definition says now.
`attemptCount` deliberately survives, so it is the evidence the job has been tried
more than once.

### Reading "what did this export hide"

`export_jobs."columns"` is the emitted set; `"authorizationSnapshot"` carries the
withheld set with a reason per column. No artefact byte needs to be read. See
`docs/migration/export-redaction-policy.md` §2.

### After a customer reports an export they should not have had

1. `export_download_events` for the job, by `exportJobId` — every attempt is there,
   refusals included, with the principal and the deny reason.
2. `export_jobs."authorizationSnapshot"` for what was withheld and why.
3. `export_deletion_events` to confirm the object was removed, with
   `legalHoldChecked = true` on every row.

## Rollback

Exports are additive and asynchronous. Rollback disables the export route set and
lets in-flight jobs drain; the job rows, the append-only events and the catalogue
stay for evidence. Templates are versioned, never mutated, so a drained job can
still be re-rendered after the fact. Object storage keys are tenant-prefixed and
opaque, so removing the module's configuration cannot make an artefact reachable by
a shorter path than the one that required it.
