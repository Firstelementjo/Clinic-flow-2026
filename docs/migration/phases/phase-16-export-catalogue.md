# Phase 16 — The export catalogue is complete enough to build a request from

## Status

Implemented, tested, and awaiting contract promotion by the orchestrator.

## The defect

`POST /api/v1/exports` requires four values in its body: `reportDefinitionId`, `reportVersion`,
`documentTemplateId`, `documentTemplateVersion`. Before this phase **no route published any of them**. The
one route that existed to publish them, `GET /api/v1/exports/catalogue`, returned the declared *field and
filter vocabulary* — subject keys, bilingual labels, redaction policies, DOCX placeholder names — and not
one addressable report definition or document template.

A caller could therefore create an export only by reading the database directly. The Phase-11 Angular
reports and exports screens were built against this contract and hit exactly that wall.

The catalogue was also untyped. It returned `Map<String, Object>`, which springdoc publishes as

```json
{"type": "object", "additionalProperties": {}}
```

— not a bare object type, but *no schema at all*. A generated client typed the response `unknown` and could
render no field of it. Two symptoms, one root cause: **the catalogue was never finished.**

Three further parameters in `CreateExportRequest` were unconstrained:

| field     | was                    | is                                    |
|-----------|------------------------|---------------------------------------|
| `format`  | `type: string`         | `enum: [CSV, DOCX]`                  |
| `locale`  | `type: string`         | `enum: [ar, en]`                     |
| `filters` | free-form object       | unchanged shape, **unknown keys refused** |

## What was delivered

### 1. The catalogue lists definitions and templates, with their versions

`ExportCatalogueService` returns, scoped to the caller's tenant and the requested project:

- every **report definition** with `reportDefinitionId` — named exactly as the create request's field, so a
  client copies it across with no translation table — plus its bilingual labels, subject, timezone,
  `localeDefault`, `requiredPermissions`, redaction policy, retention class, status and version pointers;
- every **version** of it, each with the **column keys this caller would receive**, an `exportable`
  decision, and — when it is not exportable — **why**;
- every **document template** with its versions, each with an `exportable` decision;
- the closed `formats` and `locales` vocabularies, the defaults, and the per-subject field/filter
  vocabulary that was already there.

`projectId` is now **required**. `export_jobs."projectId"` is NOT NULL because an export is always about
one project's data, so a catalogue with no project scope could only describe requests the create route
refuses. Required also means `TenantQueryGuard` validates it against the caller's server-resolved
memberships **before a statement is built** — which is where "refused before the query" comes from.

### 2. A version is offered only if POST would accept it

This is the part that is easy to get wrong and expensive to get wrong. A catalogue listing definition X's
versions would be *worse than no catalogue* if version 3 of X is a DRAFT whose columns the registry cannot
resolve, or a version whose every column is withheld from this caller: the client would offer it, the
create route would refuse it with 404 or 403, and the operator's conclusion would be that the feature is
broken.

So `VersionEntry` carries an `exportable` decision computed through **the same** `ReportFieldRegistry`
resolution and `ExportRedactionResolver` the create route uses, against the caller's own effective
permissions resolved for that project. Template versions are checked for the row *and* the stored object,
which is exactly what `DocumentTemplateService.resolve` needs.

A shared code path alone would not prove the two agree. `ExportCatalogueCapabilityIntegrationTest` closes
the loop **empirically**: it posts every version the catalogue offers and asserts acceptance, and posts a
withheld version and asserts refusal.

A `RETIRED` version is normally still exportable, deliberately: a version body is immutable and never
purged (ADR-0009 §4), so an export pinned to it stays reproducible.

### 3. `format` and `locale` are `enum` in the contract, derived from the one accepted type

`ExportVocabularyOpenApiCustomizer` declares both, from `ExportFormat.acceptedValues()` and
`ExportLocale.acceptedValues()` — the same two lists the create route refuses against. An
`@Schema(allowableValues = ...)` would have been a third list to keep in step, which is how
`/inbox/counts` came to show a permanent zero on three tabs.

The fields stay `String` in the record, deliberately. Spring binds an enum **by Java constant name**, so
typing them would have made `"csv"` a 400 while the document advertised `CSV`. The wire values are the
compatibility surface; the document carries the constraint, which is where the constraint was missing.

**The shared-Schema trap.** springdoc hands out shared model objects, so the obvious implementation —
mutate the property's schema in place — stamps the export vocabulary onto unrelated paths across the
whole contract. Phase 15 shipped exactly that bug in the inbox filter customiser and caught it only by
diffing the served document path by path. This customiser builds a **fresh** `StringSchema` and replaces
the map **entry**, leaving the shared object untouched.
`ExportVocabularyContractTest.noOtherSchemaIsContaminated` reproduces the trap by sharing an instance the
way springdoc does, and counts enum-bearing properties across the whole document rather than spot-checking.

### 4. An undeclared format or locale is refused — and the status says which kind of problem it is

| request            | status | code                      | why                                              |
|--------------------|--------|---------------------------|--------------------------------------------------|
| `format: "parquet"` | 400 | `BAD_REQUEST`             | malformed; never reaches PostgreSQL's enum cast |
| `format: "PDF"`     | 422 | `EXPORT_FORMAT_UNSUPPORTED` | a real `ExportFormat` value this renderer cannot produce |
| `locale: "fr"`      | 400 | `BAD_REQUEST`             | not a `Locale` value; cannot be stored anywhere  |

Both refusals happen **in the controller, before `jobs.create`**, so a request naming an unrenderable format
or an undeclared locale never reaches the database and never leaves a `QUEUED` row behind.

`locale` was additionally **accepted and silently ignored** before this phase: it was bound, stored on
nothing, and the render locale came from `report_definitions.localeDefault` unconditionally. A caller who
asked for English received Arabic with no way to tell from the file. The requested locale is now validated,
recorded in the job's authorization snapshot, and **honoured at generation**, with the definition's
declared default as the fallback when the request expresses no preference.

`ReportCatalogRepository.normalizeLocale` now delegates to `ExportLocale` rather than restating the switch,
so the vocabulary a declaration is checked against, the vocabulary a request is refused against, and the
`enum` published in the document are one list read from one place.

### 5. An unknown key inside `filters` is refused

Already true at creation (`ReportFilterShape.validate` refuses an undeclared key and names it); now
**asserted over HTTP**, with a positive control beside it. A declared key from the catalogue's own
subject vocabulary is accepted, so "an unknown key is refused" is not satisfied by refusing everything.

This matters because a silently-ignored filter produces an export that looks right and is wrong — and
because finance and clinical data are involved, nobody notices until the numbers are quoted.

## The test that matters

`ExportCatalogueCapabilityIntegrationTest.catalogueAloneIsEnoughToCreateAnExport`:

> a client that reads **only** `GET /api/v1/exports/catalogue`, takes a report definition, a version, a
> document template and a template version from it, posts `POST /api/v1/exports`, and gets **202**.

**Passing.** No id in the suite is hand-typed — every one is read out of the HTTP response, because a test
naming a fixture id would pass on a server whose catalogue had lost the entry.

## Security posture

- **Tenant**: `authorization.check` then `TenantQueryGuard.scope`, both before any repository call. Every
  statement carries `:__fe_tenant_organization_id`, so another tenant's entries are absent from the answer
  rather than filtered out of it.
- **Project**: validated against server-resolved memberships before SQL is built; an out-of-scope project
  is a 404, identical to a nonexistent one. `ListDefinitions`/`listTemplates` include tenant-wide rows
  (`projectId is null`) by design — those are legitimately visible to every project.
- **Fields**: the catalogue publishes column **keys**, which the pre-existing `fields` vocabulary already
  published. No SQL expression, no storage key, no content hash, no row count.
- **The one caller disclosure** is which versions *they* cannot export and why — metadata about their own
  permission set, which is the difference between a catalogue and a list of ids.
- Filters off in this suite's MockMvc, so the servlet chain's session requirement does not stand between the
  suite and the capability. `@RequirePermission` still runs — method security is an AOP proxy, not a
  filter — and the thread bindings are established per request exactly as the chain would.

## A production defect this phase found

The catalogue's honesty about exportability surfaced a real bug, which is the strongest argument for the
catalogue being honest rather than optimistic.

`DocumentTemplateService.createVersion` opened an object-store sink, wrote the template bytes, closed it —
and **never committed**. `FilesystemExportObjectStore.openForWrite` deliberately writes to a `.partial`
sibling so a killed process leaves nothing an operator could mistake for a finished artefact; moving it to
its final key is a separate `commit`. So every authored template's object sat at its `.partial` path while
its version row named the real key. `templates.resolve` reads by the stored key, found nothing, and raised
`TEMPLATE_OBJECT_MISSING` — meaning **every DOCX export would have failed at generation time**.

It shipped because no suite authored a template and then rendered from it. The template catalogue had no
round-trip test at all.

Fixed in `writeCommittedObject`, which commits on success and discards the partial on failure, mirroring
`ExportGenerationRunner`. The fixture now reads the object back by the row's own key and compares the bytes,
so the round trip cannot be lost again.

## Contract impact — the orchestrator must re-promote

`tests/contracts/openapi.json` **must be re-promoted**. Measured against the served document:

**Paths: 128 before, 128 after. None added, none removed.**

**New schemas (5):** `ExportCatalogue`, `ReportDefinitionEntry`, `VersionEntry`,
`DocumentTemplateEntry`, `TemplateVersionEntry`. Springdoc names nested records by their simple name, not
by an enclosing type — I predicted the `ExportCatalogue`-prefixed form in an earlier draft of this document
and the served document corrected me.

**Changed schemas (2):**
- `CreateExportRequest` — `format` gains `enum: [CSV, DOCX]` plus a description and example; `locale` gains
  `enum: [ar, en]` plus a description and example.
- `Money` — **property order only**, the same seven boolean properties. This is pre-existing springdoc
  nondeterminism in another module's record, not a Phase 16 change. It will appear in the promotion diff
  and is safe to accept, but it is not mine and I did not cause it.

**Also changed:** `GET /api/v1/exports/catalogue` gains a **required `projectId` query parameter**, and its
`200` response now declares `$ref: #/components/schemas/ExportCatalogue` instead of `additionalProperties: {}`.

**Contamination check, over the whole served document.** Every enum-bearing property in every schema was
enumerated (25 across the document). Exactly **two** carry an export vocabulary:

```
CreateExportRequest.format = CSV|DOCX
CreateExportRequest.locale = ar|en
```

No unrelated schema or path picked up either vocabulary. The shared-`Schema` trap that Phase 15 shipped is
not repeated.

No DDL, no migration. `LIST_TEMPLATE_VERSIONS` reads an existing table.

## Test suites added — register these with exact counts

| suite | tests | stage |
|---|---|---|
| `com.firstelement.exports.ExportCatalogueCapabilityIntegrationTest$CatalogueOnlyClient` | 4 | backend-integration |
| `com.firstelement.exports.ExportCatalogueCapabilityIntegrationTest$TenantAndProjectScope` | 4 | backend-integration |
| `com.firstelement.exports.ExportCatalogueCapabilityIntegrationTest$ClosedVocabularies` | 7 | backend-integration |
| `com.firstelement.exports.ExportCatalogueCapabilityIntegrationTest` | 0 | backend-integration |
| `com.firstelement.exports.ExportVocabularyContractTest` | 6 | backend-unit |

**+21 tests. `reporting-exports` is now 200 tests, 0 failures, 0 errors, 0 skipped, across 44 suites**
(was 179 across 38). `ExportVocabularyContractTest` needs no Docker and no database, by design.

## Known risks and follow-ups

- **The `202` is not in the contract.** `POST /api/v1/exports` returns `202 Accepted` and springdoc publishes
  `200` for it, because `ResponseEntity.accepted()` carries no `@Operation` response declaration. The suite
  asserts the true `202`. Left alone: changing it is a contract-wide decision about how this API declares
  non-200 success responses, and the orchestrator owns the document.
- **`download-policy` gained `locales`** alongside its existing `formats`, and `formats` now comes from
  `acceptedValues()` — same two values as before, one derivation point.
- **Export generation now reads the job's authorization snapshot once** for the pinned locale. Generation
  already read the detail row for applied filters; this is the same shape of access, resolved once per
  generation rather than per consumer.
- **A DOCX export is not checked against its template's locale.** A caller pinning `locale: en` against an
  Arabic template gets an English render of Arabic-labelled operator prose. Recorded rather than refused,
  because a new refusal class here is a product decision, not a contract fix. Worth a ruling.
- **`REPORT_MANAGE` is still owed.** Authoring still borrows `WORKFLOW_MANAGE`; the catalogue does not change
  that, but a read-scoped catalogue and a write-scoped authoring surface sharing one borrowed permission is
  the shape that eventually gets confused.