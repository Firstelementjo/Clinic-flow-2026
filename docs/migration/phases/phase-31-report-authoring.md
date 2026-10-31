# Phase 31 — Report authoring: from an empty catalogue to an export anyone can create

- Status: PASS
- Branch: `migration/spring-angular`
- Owner: `firstelement-exports` (reporting/exports engineer)
- Gate before this phase: 1612 tests, 0 failures, 0 skipped, 243 suites

## 1. The gap this closes

`V20261005060000__report_definitions.sql` creates four tables
(`report_definitions`, `report_versions`, `document_templates`,
`document_template_versions`) with **zero rows**, and **no route created any of
them**. On a real database `GET /api/v1/exports/catalogue` therefore answered an
empty list and `POST /api/v1/exports` — which requires
`(reportDefinitionId, reportVersion, documentTemplateId, documentTemplateVersion)`
— had nothing valid to reference. Exports could not be created at all.

Phase 16 proved the *capability* and its test passed, because
`ExportsFixture` authored the catalogue in `@BeforeEach`. The fixture is what
hid the gap. This phase closes it by **authoring**, not by seeding: no
report definition and no document template is invented by a migration, because a
report definition is authored by somebody who knows the business and a
plausible-looking wrong export is worse than an empty catalogue.

## 2. `REPORT_MANAGE`

Added to `Permission` as `REPORT_MANAGE("report:manage")`.

### Who holds it, and why

**`ADMIN` only.** `RolePermissions.ADMIN` is `EnumSet.allOf(Permission.class)`, so
the grant is a consequence of the role being the administrator role rather than a
line bolted onto the grant table.

No `Capability` entry carries it. The reason is a schema fact, not a preference:
`report_versions."createdByUserId"` and
`document_template_versions."createdByUserId"` are `NOT NULL` with a composite
tenant foreign key to `users(id, organizationId)`, while
`report_definitions` has **no author column at all**. A machine principal holding
`REPORT_MANAGE` could therefore create a report definition nobody authored and
then fail on its very first version — a catalogue entry that cannot be published,
attributed to nobody. That is the "dead configuration" state
`V20261005060000`'s Arabic-name CHECKs exist to prevent. A half-usable grant is
worse than none, so the capability is not granted it.

This is a deliberate **narrowing** of what was offered at the start of this
phase (`Capability.REPORT_WRITE` was floated as a machine grant). It is recorded
here so it can be overruled deliberately rather than re-discovered.

### The conflation that is removed

Every authoring entry point previously required `Permission.WORKFLOW_MANAGE`, so
a workflow administrator could silently change what a finance export contained,
and an audit row attributing that change to `workflow:manage` asserted something
untrue (ADR 0010 §2, "a permission is named after what it authorizes"). ADR 0011's
sibling rule and the Phase-6a precedent (`WORKFLOW_MANAGE`/`SLA_MANAGE`) both
named permissions after the thing they authorized.

`REPORT_MANAGE` and `WORKFLOW_MANAGE` are now granted to the same set of
principals (`ADMIN`), so no principal can change a workflow without also being
able to change what a finance export contains. Asserted exhaustively over every
role and every singleton **and pair** of capabilities.

## 3. Authoring routes

All on `ExportController` under `/api/v1/exports`. **13 routes added, 0 removed.**

| Method | Path | Permission |
|---|---|---|
| POST | `/api/v1/exports/report-definitions` | `report:manage` |
| GET | `/api/v1/exports/report-definitions/{definitionId}` | `analytics:read-project` |
| PATCH | `/api/v1/exports/report-definitions/{definitionId}` | `report:manage` |
| GET | `/api/v1/exports/report-definitions/{definitionId}/versions` | `analytics:read-project` |
| POST | `/api/v1/exports/report-definitions/{definitionId}/versions` | `report:manage` |
| POST | `/api/v1/exports/report-definitions/{definitionId}/versions/{n}/publish` | `report:manage` |
| POST | `/api/v1/exports/report-definitions/{definitionId}/versions/{n}/retire` | `report:manage` |
| POST | `/api/v1/exports/document-templates` | `report:manage` |
| POST | `/api/v1/exports/document-templates/{templateId}/versions` | `report:manage` |
| GET | `/api/v1/exports/document-templates/{templateId}/versions` | `analytics:read-project` |
| POST | `/api/v1/exports/document-templates/{templateId}/versions/{n}/publish` | `report:manage` |
| POST | `/api/v1/exports/document-templates/{templateId}/versions/{n}/retire` | `report:manage` |
| GET | `/api/v1/exports/authoring` | `analytics:read-project` (UI hint only) |

The template version body is the **raw DOCX**, not `multipart/form-data`: a
multipart part has to be spooled or buffered before it can be validated, which
turns a bounded document upload into an unbounded one. The bound is
`firstelement.exports.generation.max-template-bytes` (default 8 MiB, new), enforced
in the service on the bytes themselves and pre-checked against the request body
length. The honesty note is in the controller: a chunked request is bounded once
the service sees it, not before.

No authoring response carries `storageKey` or `contentHash`. A storage key is a
locator for a document in object storage and ends up in browser tabs, proxy logs
and support tickets.

`GET /api/v1/exports/authoring` publishes `mayAuthor` so an Angular screen is not
built out of speculative 404s. It is a **UX hint only**; every route refuses
regardless.

## 4. Why the authoring handlers carry no `@RequirePermission`

`@RequirePermission` is `@PreAuthorize`, and a failing expression raises
`AccessDeniedException`, which `SecurityExceptionAdvice` renders as **403**. A
cross-tenant id on the same route raises `PolicyDeniedException`, rendered as
**404** — the answer a nonexistent id gives.

One authoring surface answering 403 for "you may not author here" and 404 for
"that is not yours" is a discriminator on the surface. So the enforcement is a
single component, `ReportAuthoringGuard`, whose first statement is required by
every authoring entry point and which throws `PolicyDeniedException`.

The trade-off is stated in that class rather than hidden: the declaration is no
longer on the method signature. What compensates for it, all asserted:

1. every authoring route delegates to a service whose **first statement** is the guard;
2. the check reads a `TenantContext` argument, not ambient authentication, so it is
   enforced identically for HTTP, a console and a job;
3. a reflection test fails if `@RequirePermission(WORKFLOW_MANAGE)` reappears on any
   public method of `ReportDefinitionService`, `DocumentTemplateService` or
   `ExportController`.

The authoring handlers stayed on `ExportController` rather than a second
controller so `ExportExceptionHandler`'s `assignableTypes = ExportController.class`
selector is unchanged — `check-advice-order.ps1` FE-ADVICE-005 remains at exactly
its pre-existing 1 finding.

## 5. The version lifecycle, matched to the schema rather than invented

Read first from `V20261005060000`, then implemented:

- **One live body per definition.** `report_versions_one_published_per_definition`
  is a partial UNIQUE index on `(organizationId, reportDefinitionId) WHERE state =
  'PUBLISHED'`. `publish` retires the incumbent (naming its successor) and
  publishes the target in one transaction; a concurrent double publication loses
  **on the index** and rolls back with the incumbent still live. Asserted
  behaviourally: a direct second `PUBLISHED` update is refused with
  `DuplicateKeyException` and the state is asserted unchanged afterwards.
- **DRAFT first, so version 2 can be authored while version 1 is live.** Asserted:
  two DRAFTs coexist.
- **A published body is immutable.** Re-publishing is 409; the only transition
  left is `RETIRED`.
- **A retirement names its successor.** `report_versions_retired_check` requires
  `retiredAt` and `supersededByVersionId`; a retire request without
  `successorVersion` is 400.
- **Publication validates the placeholder vocabulary.**
  `document_template_versions_placeholder_entries_check` validates the stored
  schema shape, and `publish` now re-validates the stored placeholder **names**
  against the registry before the version goes live. `createVersion` already
  refused an unknown name before any object existed; the re-check exists because
  `placeholderSchema` is jsonb and anything that wrote the row other than
  `createVersion` — a migration, a backfill, a future authoring surface — can put a
  name in it nothing declares.
- **Arabic-first names are required**, and the schema's CHECK refuses a report
  with only an English name. The authoring request carries both and says why.
- **A real IANA zone, never the server's.** `Asia/Riyaddah` is refused here and by
  `firstelement_valid_timezone`.

## 6. A definition with no published version cannot be referenced

**New rule, brief-mandated.** `export_jobs_report_pin_check` makes the pin
all-or-nothing and says nothing about the version's state, so before this a
definition with *no published version at all* was exportable by naming its draft,
and the export succeeded — a silent success for exactly the case the catalogue
exists to prevent. A client following the catalogue saw no usable version; a client
that guessed a version number got a file out of an unreviewed configuration.

The pin must now name a version that has been **live at some point**: `PUBLISHED`
or `RETIRED`. `RETIRED` stays legal deliberately — a version body is immutable and
never purged (ADR-0009 §4), so an export pinned to a retired version stays
reproducible forever.

Applied to **both** halves of the pin, because `CreateExportRequest` carries a
template version too and leaving one half checked and the other not is the same
inconsistency in a new place. The template half is checked at **create** time, not
left to fail inside the renderer on a worker.

`ExportCatalogueService` publishes the identical decision on
`exportable`/`refusal`, and `ReportAuthoringIntegrationTest` closes the loop
empirically: it posts **every** offered version and asserts acceptance, and every
withheld one and asserts refusal. A shared code path would prove only that the
code is shared.

## 7. Three defects found by asserting the lifecycle the brief asks for

None of these was in the brief. Each was found because the suite exercises the
lifecycle rather than the code's description of it.

### 7.1 Retirement had never worked

`ReportCatalogRepository.retireVersion` and `retireTemplateVersion` set
`publishedAt = null` while moving to `RETIRED`.
`report_versions_published_state_check` is
`CHECK ((state = 'DRAFT') = (publishedAt IS NULL))` — a biconditional over DRAFT
and nothing else. The truth table makes the statement impossible:
`(false) = (true)` is false, so the database refused every retirement with
`DataIntegrityViolationException`, which the web layer answered as **500**.

Both statements now keep `publishedAt` and `publishedByUserId`. Keeping them is
also the better record: when a retired version was live is a fact about the
report's history, and an audit reader asking "which version produced the March
number" needs it.

The old comment claimed the opposite ("RETIRED is not DRAFT, so the pair must go
together") — a correct statement of the biconditional's left-hand side used to
justify an inference its right-hand side contradicts. Replaced.

### 7.2 Placeholder validation had never checked anything

`DocumentTemplateService.validatePlaceholders` did
`new String(templateBytes, UTF_8)` over the raw bytes. A DOCX is a ZIP whose
`word/document.xml` is DEFLATE-compressed, so the regex matched nothing on every
call.

It was invisible for one specific reason: a template with **zero** recognised
placeholders passes every other check. The schema's placeholder CHECK accepts an
empty array, and `DocxTemplateRenderer` renders the literal text it finds — so the
only observable consequence was a finished document still containing literal
`{{report.customer.displayName}}` in a customer-facing file. Nothing asserted that
a placeholder was ever recognised.

`documentText(byte[])` now reads every `word/*.xml` part (body, headers and
footers), bounded at 4 MiB because an unbounded read of a caller-supplied ZIP is a
decompression bomb. XML tags are left in place: braces are not XML
metacharacters, and a placeholder POI split across `<w:t>` elements must still be
seen.

### 7.3 A lost rename race answered 500

`ReportCatalogRepository.ConcurrentDefinitionChangeException` extends
`RuntimeException` directly, so it fell past every handler in
`ExportExceptionHandler` to the application's `Exception` floor and answered
`500 INTERNAL_ERROR`. A rename that lost an optimistic-concurrency race is a
conflict. Now `409 REPORT_CATALOG_CONCURRENT_CHANGE`.

### 7.4 The commit defect, hardened

`DocumentTemplateService.createVersion` now reads the object back **through the
key the version row is about to name** and compares it with what was written,
after the commit. A version row therefore cannot be written unless its own key
serves its own bytes. The Phase-16 commit defect (bytes written, never committed,
every DOCX export failing at generation) becomes an authoring-time refusal instead
of a worker failure an hour later.

## 8. Tests

`ReportAuthoringIntegrationTest` — 23 tests, no fixture row anywhere in it.
`ExportsFixture` writes organizations, projects and users and nothing else.

| Nested suite (`@DisplayName`, which is the reported name) | tests |
|---|---|
| a fresh database with nothing in it | 2 |
| without report:manage | 3 |
| report authoring is not workflow authoring | 3 |
| another tenant's catalogue | 3 |
| the version lifecycle matches the schema's rules | 10 |
| the catalogue and POST agree, empirically | 2 |

### The capability test

> On a **fresh, empty** database — asserted, not assumed: the test opens with
> `count(report_definitions) == 0` and `count(document_templates) == 0` — an
> authorised user authors a report definition over HTTP, publishes a version,
> authors a document template, stores and publishes a template version, reads the
> catalogue, posts the export and runs it to completion. **Every id and version
> number is read out of the response immediately above it. Nothing is
> hand-typed.** Terminal assertions: `COMPLETED`, `sha256:` checksum matching the
> bytes, `rowCount == 1`, `progressPct == 100`.

A companion test asserts the catalogue is empty *before* anything is authored and
that a create request built from it is refused — so "empty" is a distinguished
state rather than an absence of evidence.

### The refusals

- Without `report:manage`: **404** (non-disclosing), on all nine authoring routes,
  for an `EMPLOYEE` **and** for a `WORKER` holding `REPORT_WRITE`. Exercised
  against both a real target id and a random one, so the answer is non-disclosing
  rather than merely restrictive. Row counts asserted unchanged. Positive control:
  the same routes, same body, same project, answered for `ADMIN`.
- Cross-tenant: another tenant's definition is invisible, unreadable, unauthorable
  on every id-targeted route, and unreferenceable by `POST /api/v1/exports`;
  the same for a template. A project outside the caller's memberships is refused
  before a statement is built.
- A definition with no published version: catalogue marks the DRAFT
  `exportable: false` with a reason; `POST` answers **409
  `REPORT_VERSION_NOT_PUBLISHED`** and writes no job row. Positive control: publish
  it and the identical request succeeds.
- Template bytes round-trip: authored over HTTP, then read back **through the key
  the row itself names**, byte for byte, plus the row's `contentHash`, plus the
  service's own `resolve` path — the exact call generation makes.
- Publishing a second live version: refused by the partial unique index, state
  asserted unchanged.
- A non-DOCX body, an undeclared placeholder, an undeclared column, an oversized
  body and a non-readable zone are each refused **before any row is written**.

## 9. Migration: none was required, and here is the evidence

The brief anticipated a Flyway migration to add the enum value. **The permission
catalogue is not in the database.** Measured, not assumed:

- `Select-String ops/flyway/sql/*.sql -Pattern '^CREATE TYPE'` → **57** types.
  None is a permission vocabulary. `Locale`, `UserRole`, `ReportStatus`,
  `DocumentTemplateStatus`, `ExportFormat`, … and nothing for permissions.
- `Select-String` for `workflow:manage|sla:manage|report:manage|analytics:read-project|ai-send`
  across `ops/flyway/sql/*.sql`, `ops/flyway/verification/*.sql` and
  `ops/flyway/rollback/*.sql` → **0 hits**. The two Phase-6a permissions that
  already exist as Java constants are likewise absent from every SQL file.
- `report_definitions."requiredPermissions"` is `text[] NOT NULL DEFAULT '{}'` with
  one CHECK — `NOT firstelement_array_has_blank(...)` — a blank-value guard, not a
  vocabulary. `ReportDefinitionService.declaredPermissions` resolves every entry
  against the Java `Permission` catalogue at authoring time.
- `admin_audit_events."action"` is `text NOT NULL` with `length(btrim(action)) > 0`.

So `ops/flyway/sql/` is **unchanged**, `expected-state.json` `postBoot` needs **no
new figures**, and the fingerprint is unchanged at `bf31e9f102be19a6c373eb353b2d2131`.
The gate the orchestrator wired was run against the committed chain:

```
verify-clean-bootstrap.ps1 -Keep          -> RESULT: clean bootstrap reproduced postBoot exactly (exit 0)
    tables 98/98  enums 57/57  checks 422/422  FKs 223/223  PKs 98/98
    indexes 394/394  unique indexes 205/205  unique constraints 0/0
    triggers 11/11  nullable projectId 37/37
    fingerprint bf31e9f102be19a6c373eb353b2d2131 == bf31e9f102be19a6c373eb353b2d2131
audit-rendered-text-assertions.ps1 -Database firstelement_cleancheck_boot
    -> shipped predicates that flip: 0 ; removed patterns that flip: 5 (regression evidence)
    -> RESULT: no shipped predicate flips (exit 0)
```

The throwaway `firstelement_cleancheck_boot` database was dropped afterwards. The
`firstelement` database was never read or written by anything in this phase.

## 10. Contract promotions required (not done here — not my ownership)

### `tests/contracts/openapi-paths.json` — 13 routes added, 0 removed

```
.\ops\quality\checks\export-openapi-paths.ps1 -Out .\tests\contracts\openapi-paths.json
```

The 13 are exactly the table in §3. `check-openapi-contract.ps1 -Diff` currently
reports `routes added: 13, routes removed: 0` and FAILs, which is the check doing
its job: it exists to make the contract diff mandatory and reviewable.

### `tests/contracts/expected-suites.json` — 7 entries

`check-no-hidden-skips.ps1` §3b **fails on unregistered suites**. All 23 tests live
inside `@Nested` classes, so JUnit reports them under their `@DisplayName`, not the
class name. The container emits no report of its own, so it needs the `containerOf`
marker its two siblings already have.

```json
{ "suite": "com.firstelement.exports.ReportAuthoringIntegrationTest",
  "containerOf": "com.firstelement.exports.ReportAuthoringIntegrationTest",
  "module": "reporting-exports", "stage": "backend-integration",
  "minTests": 0, "kind": "container", "services": ["postgresql", "pgvector"],
  "note": "Phase 31 container marker. Six nested suites, floors summing to 23." }

{ "suite": "com.firstelement.exports.ReportAuthoringIntegrationTest$FreshDatabase",
  "reportName": "a fresh database with nothing in it", "minTests": 2, "kind": "integration" }
{ "suite": "com.firstelement.exports.ReportAuthoringIntegrationTest$WithoutReportManage",
  "reportName": "without report:manage", "minTests": 3, "kind": "security" }
{ "suite": "com.firstelement.exports.ReportAuthoringIntegrationTest$NoWorkflowConflation",
  "reportName": "report authoring is not workflow authoring", "minTests": 3, "kind": "security" }
{ "suite": "com.firstelement.exports.ReportAuthoringIntegrationTest$CrossTenant",
  "reportName": "another tenant's catalogue", "minTests": 3, "kind": "security" }
{ "suite": "com.firstelement.exports.ReportAuthoringIntegrationTest$VersionLifecycle",
  "reportName": "the version lifecycle matches the schema's rules", "minTests": 10, "kind": "integration" }
{ "suite": "com.firstelement.exports.ReportAuthoringIntegrationTest$CatalogueAgreesWithPost",
  "reportName": "the catalogue and POST agree, empirically", "minTests": 2, "kind": "integration" }
```

`kind: "security"` on three of them is not decoration: they are the suite a
reviewer reads to answer "may a workflow administrator change what a finance export
contains".

### `frontend/src/app/core/auth/permissions.ts`

`workflowManage` and `slaManage` are already there; `reportManage:
'report:manage'` is missing. Outside this phase's ownership, so not added. Until it
is, the Angular client cannot express the authoring screen's permission.

`packages/auth/src/policy.ts` (the frozen legacy NestJS vocabulary) is untouched,
consistent with `workflow:manage`, `sla:manage` and `ai-send` never having been
added there either.

## 11. Known residuals

1. **`REPORT_MANAGE` is ADMIN-only.** Every reporting administrator is a tenant
   administrator. A deployment wanting a reporting officer who may author but not
   administer needs a role or a capability carrying it, which means either a
   `RolePermissions` change (and an audit decision about which existing grant
   widens) or a service-principal author column on `report_definitions`. Recorded
   rather than guessed.
2. **`DELETE` does not exist for definitions or templates.** ADR-0009 §4 classes
   both tables never-purged, so a definition is renamed and a version retired. The
   authoring surface exposes no delete route at all, which is the honest shape.
3. **`retire` does not move `report_definitions."publishedVersion"`.** The schema
   has no CHECK on that pairing, and the pointer is a denormalised convenience; a
   definition can therefore read `publishedVersion: 1` while version 1 is
   `RETIRED`. The catalogue shows both facts and an export pinned to version 1
   still works, which is the behaviour that matters. Reported, not changed, because
   "fixing" it is a schema question.
4. **`GET /api/v1/exports/authoring` is unauthenticated-adjacent advice**: it tells
   a caller whether they hold `report:manage`. That is metadata about their own
   permission set, the same category `ExportCatalogueService` already publishes in
   its per-version `refusal` field.
5. **`Content-Length` pre-check is advisory.** A chunked upload is bounded by the
   service, not before it. Stated in the controller javadoc rather than implied.
6. **`evidence/` is untracked and not gitignored.** Running
   `verify-clean-bootstrap.ps1` created `evidence/phase-30/clean-bootstrap/`. It is
   the gate's own default output; decide whether to gitignore it or commit it.

## 12. Reported, not fixed — AI governance

`ai_policies.customerVisibleRequiresApproval` defaults to `true`
(`V20261005100000:298`), so a per-tenant AI policy row refuses every
customer-visible reply until somebody opts out. AI governance's to decide, and
correctly so: the safe default is right and the silent-ness of it is the problem.