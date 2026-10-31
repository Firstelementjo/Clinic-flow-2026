# Phase 32 - Authoring contract and refusals: a client that reads the contract can build a request that succeeds

- Status: PASS
- Branch: `migration/spring-angular`
- Owner: `firstelement-exports` (reporting/exports engineer)
- Gate before this phase: 1635 tests, 0 failures, 0 skipped, 249 suites
- Gate for this module after this phase: 333 tests, 0 failures, 0 skipped, 53 suites

## 1. What the Angular agent found, and what it was

The agent drove the real API rather than trusting the document, and six requests
the contract described could not succeed. One of them - authoring a version with
an **empty `measures`** - was answered **`500`**, logged at ERROR, for a client
mistake. `plan.md` requires that a request the server cannot answer be a
**refusal, never a 500**.

The cause was not a missing validation. It was a CHECK constraint firing:

```
report_versions_measures_present_check  CHECK (jsonb_array_length("measures") >= 1)
```

Nothing refused first. `requireMeasuresAreMeasures` returns early for `null` and
an empty list has nothing to reject, so `[]` was written and the CHECK fired. A
`DataIntegrityViolationException` has no handler in `ExportExceptionHandler`, so
it reached `app-bootstrap`'s `ErrorHandlingAdvice` floor and became
`500 INTERNAL_ERROR`.

The other five were the same defect one layer up: the document declared fields
optional that the server requires. That is the catalogue's Phase-16 defect
("a value the server publishes must not be narrower than what it actually does")
with the published value being a request schema instead of an `enum`.

## 2. The six rules, and how each is now answered

| rule | before | now |
| --- | --- | --- |
| `retentionClass` required | `400 A report definition requires 'retentionClass'` | unchanged, plus the constraint named |
| `redactionPolicy` required | `400 ... names the redaction policy 'null'` | `400 A report definition requires 'redactionPolicy' (report_definitions_redaction_policy_check: ...)` |
| `timezone` required | `400 A report definition requires 'timezone'` | unchanged, plus the constraint named |
| version `columns` non-empty | `422 REPORT_COLUMN_UNDECLARED` | `400 A report version requires at least one entry in 'columns' (report_versions_columns_shape_check: ...)` |
| version `measures` non-empty | **500** | `400 A report version requires at least one entry in 'measures' (report_versions_measures_present_check: ...)` |
| retirement needs a successor | `400 A retirement must name the version that replaced this one...` | `400 A retirement requires 'successorVersion' (report_versions_retired_check: ...)`, and it is enforced **after** authorization |

### Why `columns` moved from 422 to 400

`columns` was not a 500, but it was a refusal that named the wrong thing:
`Report column '<none>' is not declared` tells a caller that a column they named
is unknown, when what actually happened is that they omitted a **field**. Those
are different problems with different fixes, and the second one is fixed by
adding a property. `REPORT_COLUMN_UNDECLARED` (422) still answers a genuinely
undeclared **name** - that half is unchanged and still tested.

## 3. The sweep: every CHECK on the four authoring tables

Every CHECK constraint declared by `V20261005060000__report_definitions.sql` on
`report_definitions`, `report_versions`, `document_templates` and
`document_template_versions`, and whether a caller can reach it without a
pre-check. "Reachable from client input" means the value comes out of a request
body rather than from the server's own state.

### `report_definitions` (12 CHECKs)

| constraint | reachable from a request? | gate |
| --- | --- | --- |
| `report_definitions_key_check` | yes | `AuthoringRequirements` (was `requireText`) |
| `report_definitions_name_ar_check` | yes | `AuthoringRequirements` (was `requireText`) |
| `report_definitions_name_en_check` | yes | `AuthoringRequirements` (was `requireText`) |
| `report_definitions_timezone_check` | yes | `AuthoringRequirements` + `requireTimezone` |
| `report_definitions_redaction_policy_check` | yes | `AuthoringRequirements` + `policyOf` |
| `report_definitions_retention_class_check` | yes | `AuthoringRequirements` (was `requireText`) |
| `report_definitions_required_permissions_check` | yes | `declaredPermissions` (already, Phase 31) |
| `report_definitions_published_pair_check` | no | server-derived: `publishVersion` moves both columns together |
| `report_definitions_published_version_check` | no | server-derived: `markPublished` sets status, version and timestamp together |
| `report_definitions_current_version_check` | no | server-derived: `advanceCurrentVersion` with a number it allocated |
| `report_definitions_published_version_positive_check` | no | server-derived, same statement |
| `report_definitions_version_check` | no | server-derived, `version = version + 1` |

### `report_versions` (17 CHECKs)

| constraint | reachable from a request? | gate |
| --- | --- | --- |
| **`report_versions_measures_present_check`** | **yes** | **WAS NOT GATED - a 500. Fixed: `AuthoringRequirements.CREATE_REPORT_VERSION`** |
| `report_versions_columns_shape_check` | yes | **WAS NOT GATED - a 422 naming a column when the field was omitted. Fixed: same rule** |
| `report_versions_measures_shape_check` | no | the service always writes an array via `json.write(List)` |
| `report_versions_dimensions_shape_check` | no | same |
| `report_versions_filters_shape_check` | no | same, always an object |
| `report_versions_default_sort_shape_check` | no | `ReportSortShape.toStored` always returns a list |
| `report_versions_denominator_rules_shape_check` | no | always an object |
| `report_versions_metric_semantics_shape_check` | no | always an object |
| `report_versions_measures_entries_check` | yes | `requireMeasuresAreMeasures` resolves every key |
| `report_versions_dimensions_entries_check` | yes | `requireDimensionsAreDimensions` |
| `report_versions_columns_entries_check` | yes | `ReportFieldRegistry.resolve` |
| `report_versions_sort_entries_check` | yes | `ReportSortShape.toStored` emits `{field,direction}` only |
| `report_versions_version_number_check` | no | server-allocated from `currentVersion + 1` |
| `report_versions_published_pair_check` | no | server-derived |
| `report_versions_published_state_check` | no | server-derived (the Phase-31 biconditional finding) |
| `report_versions_retired_check` | yes | `AuthoringRequirements.RETIRE_VERSION` (was the controller) |
| **`report_versions_supersede_self_check`** | **yes** | **WAS NOT GATED - a 500. Fixed: `SelfSupersessionRefusal`** |

### `document_templates` (9 CHECKs)

| constraint | reachable from a request? | gate |
| --- | --- | --- |
| `document_templates_key_check` | yes | `AuthoringRequirements` (was `requireText`) |
| `document_templates_name_ar_check` | yes | `AuthoringRequirements` |
| `document_templates_name_en_check` | yes | `AuthoringRequirements` |
| `document_templates_timezone_check` | yes | `AuthoringRequirements` + `requireTimezone` |
| `document_templates_kind_check` | no | the service pins `DOCX`; `kind` is not on the request |
| `document_templates_published_pair_check` | no | server-derived |
| `document_templates_published_state_check` | no | server-derived |
| `document_templates_version_pointer_check` | no | server-derived |
| `document_templates_version_check` | no | server-derived |

### `document_template_versions` (9 CHECKs)

| constraint | reachable from a request? | gate |
| --- | --- | --- |
| **`document_template_versions_supersede_self_check`** | **yes** | **WAS NOT GATED - a 500. Fixed: `SelfSupersessionRefusal`** |
| `document_template_versions_storage_key_check` | no | `ExportObjectStore.allocateKey` mints `<tenant>/<random>.<ext>`; no caller supplies a key |
| `document_template_versions_content_hash_check` | no | `sha256` of the bytes just written |
| `document_template_versions_placeholder_shape_check` | no | `placeholderSchema(...)` always builds an object |
| `document_template_versions_placeholder_entries_check` | yes | every name comes from the `{{report|meta|totals.<key>}}` regex, which cannot match an empty string |
| `document_template_versions_version_number_check` | no | server-allocated |
| `document_template_versions_published_pair_check` | no | server-derived |
| `document_template_versions_published_state_check` | no | server-derived |
| `document_template_versions_retired_check` | yes | `AuthoringRequirements.RETIRE_VERSION` (was the controller) |

**Three caller-reachable CHECKs had no pre-check: `measures_present`,
`columns_shape` (as a required-field question) and the two
`*_supersede_self_check` constraints. The two supersede-self ones were found by
the sweep, not by a caller: `retire v3` naming v3 as its own successor is
reachable by the same route with the same effort as the empty `measures`.**

### One more, not a CHECK

`{"defaultSort": [null]}` bound a null element and `entry.lastIndexOf(':')`
threw `NullPointerException`. Same defect, one hop away, so it is refused by name
in both `ReportDefinitionService.requireSortProjects` and `ReportSortShape`.

## 4. `RefusalText`: an absent value is `(absent)`, never `'null'`

The agent reported `400 ... names the redaction policy 'null'` for a request that
**omitted** the field. Java concatenation renders `null` as four characters, so
the message told the caller they sent the word "null" and told an operator a
policy was rejected when none was ever offered.

The required-field check now answers that request first, which is the correct
answer - but the stringifier underneath is still wrong for every other absent
value, so the rendering is fixed at the source. `RefusalText.of(null)` is
`(absent)`; a blank string is still quoted as `''`, because a caller that
serialises an unset form field sends `""` and that is a different request from
omitting the property.

Applied to `policyOf`, `ReportFieldRegistry`, `ReportSortShape`, `ExportFormat`,
`ExportLocale`, `requireTimezone` and both dimension/measure refusals. Every one
of the other messages is **byte-identical** for a non-null value, so nothing
outside this module's own tests could have depended on the old text.

## 5. The contract

### 5.1 One table, enforced and published

`AuthoringRequirements` holds one `Required` per rule: the property, its shape,
**the CHECK constraint that requires it**, why, and any closed vocabulary. It is
the single source for two consumers:

- `Request.require(Map)` - what `ReportDefinitionService`,
  `DocumentTemplateService` refuse against;
- `AuthoringContractOpenApiCustomizer` - what the served document publishes as
  each request model's `required` array.

Sixteen required fields across five models, all of them now published:
`CreateReportDefinitionRequest` (6), `RenameReportDefinitionRequest` (3),
`CreateReportVersionRequest` (2), `RetireVersionRequest` (1),
`CreateDocumentTemplateRequest` (4).

### 5.2 `required` alone is not enough

OpenAPI's `required` says the property must be **present**. It says nothing about
an array being non-empty, and `[]` satisfies it - which is exactly the value
`report_versions_measures_present_check` refuses, the request that was a 500. So
each rule also stamps the bound its shape implies: `minItems: 1` for a
collection, `minLength: 1` for a string.

### 5.3 Success codes (ADR 0016)

ADR 0016 rule 1 is "observe, then declare"; rule 3 forbids declaring a code a
route never returns. All thirteen authoring routes are declared, and each entry is
compared against the observed status by the tests:

| routes | code | was declared |
| --- | --- | --- |
| `POST /report-definitions`, `POST .../versions`, `POST /document-templates`, `POST /document-templates/{id}/versions` | **201** | 200 |
| `POST .../versions/{n}/retire` (report and template) | **204** | 200 |
| `GET .../{id}`, `GET .../versions`, `POST .../publish` (both sides), `PATCH .../{id}`, `GET /authoring` | 200 | 200 |

The response springdoc already computed is **re-keyed**, not rebuilt, so the
content schema it inferred for the return type survives. The `ApiResponse` object
itself is shared with any other operation declaring the same return type, so only
the map entry is written to.

### 5.4 One more closed vocabulary

`redactionPolicy` was a free string in the document while the service refuses
everything outside four names. It is now an `enum` derived from
`ExportRedactionPolicy.declared()` - the same list `policyOf` refuses against and
`GET /api/v1/exports/authoring` publishes.

`timezone` and `retentionClass` get descriptions rather than enums, and this is
deliberate: the set of real IANA zones is not an enum any generator could use, and
`retentionClass` is only required to be non-blank by the schema, so publishing an
enum would be a narrower statement than the server makes.

## 6. What was deliberately NOT changed

- **The refusal model.** A draft version still answers `exportable: false` with
  *"This version is a DRAFT and has never been published, so an export may not pin
  it."*, and `exportable: true` with an empty refusal after publish.
- **The non-disclosing 404.** Kept, and made *more* uniform: `expectedVersion`
  and `successorVersion` used to be enforced in the **controller**, which ran
  before the service's authorization guard, so a caller without `report:manage`
  got `400` on those two and `404` everywhere else. Both are now in the services,
  after authorization and after the target is resolved. Two tests assert this: a
  malformed body is `404` for an unauthorised caller, and `404` for a foreign
  target with a `400` control on the caller's own row - so the difference between
  a `400` and a `404` cannot be used to learn whether a row exists elsewhere.

## 7. Red to green

The Phase-31 suite was green while this feature was unreachable, so "it is green"
proves nothing on its own. The validations were reverted and the new suite run
against the pre-Phase-32 behaviour:

```
:backend:reporting-exports:test --tests "com.firstelement.exports.AuthoringRefusalIntegrationTest"
39 tests completed, 36 failed
```

Representative failures, with the exact exception each one was:

```
an empty measures array answers 400, not 500, and writes nothing
  -> DataIntegrityViolationException ... SQL [insert into report_versions ...]
a report version retired naming itself is refused, and stays published
  -> DataIntegrityViolationException ... SQL [update report_versions
a template version retired naming itself is refused, and stays published
  -> DataIntegrityViolationException ... SQL [update document_template_versions
a null sort entry is refused by name, not answered with a NullPointerException
  -> NullPointerException: Cannot invoke "String.lastIndexOf(int)" because "entry" is null
no refusal quotes an absent value as the word null
  -> NullPointerException: Cannot invoke "String.trim()" because "timezone" is null
everyRequiredFieldAbsentIsARefusal (all 16)      -> 500-shaped, one per rule
everyBlankableFieldIsARefusal (all 14)            -> 500-shaped, one per rule
```

The three that stayed green are the regression guards that are not about refusal
shape: the contract-driven request (the document still declared the fields, so the
request it builds was still complete) and the two served-document assertions.

Note on how the 500 presents: the exports test harness has **no application-level
`Exception` floor**, because `app-bootstrap` is not on this module's classpath, so
MockMvc sees the exception escape. In a deployment `ErrorHandlingAdvice` catches
it and answers `500 INTERNAL_ERROR`. Both are "not a refusal"; the second is the
one that pages.

Restored, the whole module is green:

```
:backend:reporting-exports:test --rerun-tasks
BUILD SUCCESSFUL
333 tests, 0 failures, 0 skipped, 53 suites
```

## 8. Tests added

`AuthoringContractTest` (no Spring, no database - a contract defect must be
provable on a host with no Docker):

- every rule is published as `required` on its model (**the detector**: the
  expectation is read from `AuthoringRequirements`, so a field the server requires
  and the document calls optional fails here);
- every required property carries `minItems: 1` or `minLength: 1`;
- every required property's description names the constraint that requires it;
- `redactionPolicy` is published as the enum `policyOf` accepts - both directions,
  by calling the accepting code;
- all thirteen routes declare their observed code, and nothing else;
- the route table is unambiguous, in scope, and has the four 201s and two 204s;
- a vanished route, a vanished model, an unexpressible requirement, a missing
  property and a route declaring two codes all **fail the export** rather than
  skipping;
- **no unrelated model, property or parameter gains a bound, an enum or a required
  list** - the shared-`Schema` trap reproduced with genuinely shared instances, as
  the Phase-15 inbox leak was;
- the two customisers compose;
- every requirement names a component the request record really has;
- the constraint sweep, constraint by constraint, against the table above.

`AuthoringRefusalIntegrationTest` (Spring + Testcontainers, real HTTP):

- every rule **absent** is a `400` naming the field, naming the constraint, never
  quoting `'null'`, and writing nothing - 16 cases;
- every blankable rule **blank or empty** is the same - 14 cases;
- the empty-`measures` headline: `400`, no version row, `currentVersion` unmoved,
  **and the next successful authoring is version 1** so no number was consumed;
- both self-supersession refusals: `409`, `VERSION_SUPERSEDES_SELF`, state
  unchanged;
- the null sort entry, the `'null'` sweep, the two 404-uniformity cases;
- **a request shaped only by the served document's `required` array succeeds first
  time** - for all three authoring creates - which is the end-to-end form of the
  Phase-32 finding and catches a mismatch from both ends at once;
- **the served document declares 201 and 204** on the routes that return them.

The three request shapes come from `AuthoringRequirements`, not from a list in
the test: a list would prove the list.

## 9. Commands

```powershell
$env:JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"

cmd /c 'gradlew.bat -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true" ^
  :backend:reporting-exports:test --tests "com.firstelement.exports.AuthoringContractTest" --console=plain'

cmd /c 'gradlew.bat -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true" ^
  :backend:reporting-exports:test --tests "com.firstelement.exports.AuthoringRefusalIntegrationTest" --console=plain'

cmd /c 'gradlew.bat -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true" ^
  :backend:reporting-exports:test --rerun-tasks --console=plain'

powershell -File ops\quality\checks\check-architecture.ps1          # VERDICT: PASS
powershell -File ops\quality\checks\check-advice-order.ps1          # VERDICT: PASS (FE-ADVICE-005 pre-existing)
powershell -File ops\quality\checks\check-no-hidden-skips.ps1 -ResultsDir backend\reporting-exports\build\test-results
                                                                 # VERDICT: PASS, 333 tests, 0 skips
```

No migration was added and none is proposed: every constraint this phase needed
already existed, and the defect was that the code was not checking first.

## 10. For the orchestrator

1. **`tests/contracts/openapi.json` needs re-promoting.** The served document now
   differs in five ways: `required` arrays on five authoring models; `minItems: 1`
   on `columns`/`measures` and `minLength: 1` on nine strings; an `enum` on
   `redactionPolicy`; `201` on four POSTs and `204` on two; appended descriptions
   naming each constraint. Run `ops/quality/checks/export-openapi-document.ps1`
   and promote.
2. **`POST /api/v1/exports` still declares `200` and returns `202`.** That is the
   original ADR-0016 instance and it is one line in the same customiser, but it is
   a lifecycle route and the brief scoped this phase to the authoring surface, so
   it is left for the ADR-0016 phase rather than edited from here.
3. **The Angular agent's comments in `authoring.component.ts` are now stale.** They
   quote `400 ... names the redaction policy 'null'` and describe the six fields
   as "required by the server, optional in the contract". Both are now fixed. The
   validators are unaffected - nothing in that component matches on server message
   text - but the comments should be updated by whoever owns the file.
4. **`frontend/e2e/authoring-journeys.spec.ts` line 112** quotes the
   `retentionClass` message; it still matches (the message is a superset), but it
   should be re-run against the new document.