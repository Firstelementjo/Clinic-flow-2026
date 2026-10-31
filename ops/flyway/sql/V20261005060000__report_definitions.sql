-- =====================================================================
-- V20261005060000__report_definitions.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005050000 and numerically greater than the whole Phase-6a chain.
--   See V20261005010000__workflow_definitions_and_versions.sql for why a
--   12-digit version sorts BEFORE the 14-digit baseline.
--
-- PROMOTED FROM ops/flyway/pending/V202610040005__report_definitions.sql.todo
--   (deleted on promotion).
--
-- WHY THIS MIGRATION COMES BEFORE V20261005070000__export_jobs.sql
--   The draft for export jobs carries `"reportDefinitionId" uuid` with a
--   foreign key to `report_definitions`, and a migration must never reference an
--   object a LATER migration creates. So the two Phase-7-10a report/export
--   migrations are ordered report-definitions-then-export-jobs. Both belong to
--   the same priority group, so this changes no priority, only the order within
--   it, and it is recorded here because the version order is otherwise not
--   derivable from the filenames.
--
-- WHY A REPORT IS DATA, NOT CODE
--   plan.md and FR-8.1/FR-8.2: a report is configured and VERSIONED so a
--   historical number can be reproduced after the report has been edited. If the
--   definition lived in application code, "the number we reported in March"
--   would be unanswerable the moment anyone touched the query. So the
--   dimensions, measures, filters, sort, timezone, required permissions,
--   redaction policy and retention class are all stored, and a version is
--   immutable once published.
--
-- THE ALLOW-LIST IS THE SECURITY BOUNDARY, AND THE SCHEMA SAYS SO
--   `dimensions`, `measures`, `filters` and `defaultSort` are JSONB, and each is
--   CHECKed to be a JSON ARRAY or OBJECT. They are never interpolated into SQL:
--   they are keys into a typed registry that the application resolves, and the
--   comment that says so is the contract. The first draft of this migration
--   allowed `dimensions` to be any JSON; the CHECK exists because "a JSON
--   document in a column named dimensions" is indistinguishable from "arbitrary
--   SQL someone was going to interpolate". Storing the allow-list as data and
--   enforcing its SHAPE here is what makes the difference between the two
--   reviewable.
--
-- WHY `joins` IS NOT HERE
--   The draft had a `joins` column. It is deliberately dropped: a stored join
--   graph is a query language, and a query language stored as JSONB is how a
--   report definition becomes a cross-tenant read. A report version names
--   measures and dimensions; the query layer owns how they are joined, and that
--   is code a reviewer can read. This is the research synthesis's "typed core
--   plus versioned metadata registry, not an unbounded dynamic schema", applied
--   literally.
--
-- WHY report_definitions."status" HAS A DRAFT VALUE FIRST
--   The throwaway rehearsal in Phase 6a found this exact deadlock in the SLA
--   calendar design: without DRAFT there is no way to author version 2 while
--   version 1 is published, because inserting it as PUBLISHED is refused by the
--   one-published-per-key partial index. DRAFT is therefore the FIRST enum value
--   and the table default: the state that is safe to write without thinking.
--
-- ONE PUBLISHED VERSION PER DEFINITION, ENFORCED BY A PARTIAL INDEX
--   A definition may have many versions but exactly one PUBLISHED body, so a
--   reader never has to guess which one is live. `RETIRED` is reachable only
--   from PUBLISHED (a version nobody ever published cannot be retired), and a
--   RETIRED version names what replaced it -- the same "a rollback that does not
--   say where it landed is not a rollback" rule Phase 6a applied to workflow
--   versions.
--
-- ARABIC-FIRST COPY IS NOT OPTIONAL
--   `nameAr` and `descriptionAr` are NOT NULL and the CHECK requires both an
--   Arabic and an English name. PROJECT_REQUIREMENTS is Arabic-first and the
--   clinical design constraints are part of the product, so a report with only
--   an English name is dead configuration and the database refuses it rather
--   than letting it reach a dashboard.
--
-- `timezone` IS NOT NULL AND NEVER A SERVER DEFAULT
--   A report that buckets by day must not inherit the server's timezone: the same
--   report would produce different numbers on two workers. An IANA zone name is
--   stored, and the same local-wall-clock semantics as
--   `sla_calendars.timezone` in V20261005030000 are used, so a report and an SLA
--   calendar agree about what a day is.
--
-- WHAT IT CREATES
--   1. enum  public.ReportStatus         (DRAFT, PUBLISHED, RETIRED)
--   2. enum  public.DocumentTemplateStatus (DRAFT, PUBLISHED, RETIRED)
--   3. table public.report_definitions
--   4. table public.report_versions             -- APPEND-ONLY once written
--   5. table public.document_templates
--   6. table public.document_template_versions   -- APPEND-ONLY once written
--
-- `dashboard_widgets` FROM THE DRAFT IS DEFERRED, ON PURPOSE
--   The draft sketched it. It is not created here: a saved dashboard layout is
--   Phase 7/8 UI state with no invariant this schema can enforce that the
--   existing `views` table does not already cover, and adding a table whose
--   lifecycle nobody has decided is how a schema grows a dead table. Recorded
--   here so it is a decision rather than an omission. Same for the draft's open
--   question about a metric catalogue: that belongs to whoever owns the metric
--   definitions, in their own migration.
--
-- TENANT SCOPE
--   organizationId uuid NOT NULL on all four tables (FR-1.2). projectId NULLABLE
--   on all four and that is the deliberate organisation-wide case: a tenant's
--   report catalogue is not per project, and forcing per-project copies is how
--   two reports with the same key drift apart. V12 section 4d lists them
--   explicitly so the exemption class cannot widen unnoticed.
--   `report_versions` and `document_template_versions` carry their own
--   organizationId even though the parent already has one, for the same reason
--   Phase 6a's `workflow_versions` does: a version is read on its own (an export
--   pins reportVersion), and a tenant filter that has to join through the parent
--   to know whether a row is visible is a cross-tenant read waiting to happen.
--   The composite foreign key below then REFUSES a version belonging to another
--   tenant's definition, which a single-column FK would permit.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone; every default is
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); updatedAt is never defaulted.
--   `publishedAt` is written explicitly so the publication moment is the
--   operator's, not the transaction's.
--
-- SAFETY
--   CREATE TYPE / CREATE TABLE only. No DROP, no RENAME, no ALTER of an
--   existing object, no data change. No index is added to any pre-existing
--   table.
--   Rollback: drop the four tables and the two types, in reverse dependency
--   order -- rehearsed on a throwaway database only
--   (ops/flyway/rollback/undo_phase7_10a.sql).
--   Every index name stays under PostgreSQL's 63-byte limit and the guard
--   asserts every index on these tables is one this file declared, by its exact
--   name -- the check that actually detects silent truncation.
--   Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

CREATE TYPE "public"."ReportStatus" AS ENUM (
    'DRAFT',
    'PUBLISHED',
    'RETIRED'
);

CREATE TYPE "public"."DocumentTemplateStatus" AS ENUM (
    'DRAFT',
    'PUBLISHED',
    'RETIRED'
);

-- ---------------------------------------------------------------------
-- A real IANA-zone validator.
--
-- WHY A FUNCTION AND NOT A SUBQUERY IN THE CHECK
--   PostgreSQL refuses a subquery in a CHECK constraint ("cannot use subquery in
--   check constraint"), and the alternative -- `timezone ~ <regex>` -- accepts
--   `Asia/Riyaddah`, which is a perfectly shaped zone name that does not exist.
--   A report that buckets by a nonexistent zone silently buckets by UTC or
--   throws at render time, and either way a monthly number is wrong in a way
--   nobody finds until reconciliation. This function is STABLE, not IMMUTABLE,
--   because it reads pg_timezone_names; that is honest about the fact that the
--   catalog can change, and a CHECK may call any function.
--
--   It is created in `public` with an explicit name so it cannot collide with a
--   future migration's helper, and the undo drops it.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."firstelement_valid_timezone"("candidate" text)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
    SELECT "candidate" IS NOT NULL
       AND length(btrim("candidate")) > 0
       AND "candidate" IN (SELECT name FROM pg_timezone_names)
$$;

COMMENT ON FUNCTION "public"."firstelement_valid_timezone"(text) IS
    'Phase 7-10a: true when the argument is a zone name PostgreSQL actually knows. '
    'Used by report_definitions.timezone and document_templates.timezone.';

-- ---------------------------------------------------------------------
-- Array / JSONB list validators.
--
-- WHY FUNCTIONS AGAIN
--   PostgreSQL also refuses `unnest(...)` and `jsonb_array_elements(...)`
--   inside a CHECK constraint -- both are set-returning, and a CHECK may not
--   contain a subquery. The plain-CHECK alternative for a text[] is
--   `NOT (arr @> ARRAY[''])`, which catches the empty string but not the
--   whitespace-only one, and it says nothing about the JSONB lists. So the two
--   checks that actually matter -- "every permission is a real permission" and
--   "every allow-list entry is a non-empty string" -- are functions.
--
--   These functions read nothing, so they are IMMUTABLE and can be used in
--   indexes and in generated columns as well as in CHECKs.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."firstelement_array_has_blank"("candidate" anyarray)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    element text;
BEGIN
    IF "candidate" IS NULL THEN
        RETURN false;
    END IF;
    FOREACH element IN ARRAY "candidate" LOOP
        IF element IS NULL OR length(btrim(element)) = 0 THEN
            RETURN true;
        END IF;
    END LOOP;
    RETURN false;
END
$$;

COMMENT ON FUNCTION "public"."firstelement_array_has_blank"(anyarray) IS
    'Phase 7-10a: true when any element is NULL or blank. Used so a configured '
    'permission list cannot contain a value nobody can hold.';

CREATE OR REPLACE FUNCTION "public"."firstelement_json_list_is_string_array"("candidate" jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    element jsonb;
BEGIN
    IF "candidate" IS NULL OR jsonb_typeof("candidate") <> 'array' THEN
        RETURN false;
    END IF;
    FOR element IN SELECT e FROM jsonb_array_elements("candidate") AS e LOOP
        IF jsonb_typeof(element) <> 'string'
           OR length(btrim(element #>> '{}')) = 0 THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END
$$;

COMMENT ON FUNCTION "public"."firstelement_json_list_is_string_array"(jsonb) IS
    'Phase 7-10a: true when the value is a JSON array of non-empty strings. This is '
    'the shape check that turns a report allow-list into a versioned metadata registry '
    'rather than an unbounded dynamic schema.';

CREATE OR REPLACE FUNCTION "public"."firstelement_json_list_is_sort"("candidate" jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    element jsonb;
BEGIN
    IF "candidate" IS NULL OR jsonb_typeof("candidate") <> 'array' THEN
        RETURN false;
    END IF;
    FOR element IN SELECT e FROM jsonb_array_elements("candidate") AS e LOOP
        IF jsonb_typeof(element) <> 'object'
           OR NOT (element ? 'field')
           OR length(btrim(element ->> 'field')) = 0
           OR lower(coalesce(element ->> 'direction', '')) NOT IN ('asc', 'desc') THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END
$$;

COMMENT ON FUNCTION "public"."firstelement_json_list_is_sort"(jsonb) IS
    'Phase 7-10a: true when the value is a JSON array of {"field":...,"direction":"asc|desc"} '
    'objects. A sort entry with no direction is a suggestion the reader cannot act on.';

-- ---------------------------------------------------------------------
-- report_definitions
-- ---------------------------------------------------------------------
CREATE TABLE "public"."report_definitions" (
    "id"                   uuid NOT NULL,
    "organizationId"       uuid NOT NULL,
    "projectId"            uuid,
    -- Stable machine name. UNIQUE PER TENANT, never globally: a global index
    -- would turn "that key already exists" into a cross-tenant existence
    -- oracle, which is a disclosure in its own right.
    "key"                  text NOT NULL,
    "nameAr"               text NOT NULL,
    "nameEn"               text NOT NULL,
    "descriptionAr"        text,
    "descriptionEn"        text,
    "status"               "public"."ReportStatus" NOT NULL DEFAULT 'DRAFT',
    -- IANA zone. NOT NULL and never defaulted to the server's zone: a report
    -- that buckets by day must not produce different numbers on two workers.
    "timezone"             text NOT NULL,
    "localeDefault"        "public"."Locale" NOT NULL DEFAULT 'ar',
    -- The permissions a caller MUST hold, in addition to their own effective
    -- permissions. Additive, never a substitute: FR-8.2 is applied by the query
    -- layer, and this column is the declaration it reads.
    "requiredPermissions"  text[] NOT NULL DEFAULT '{}',
    "redactionPolicy"      text NOT NULL,
    "retentionClass"       text NOT NULL,
    "currentVersion"       integer,
    "publishedVersion"     integer,
    "publishedAt"          timestamp(3) without time zone,
    "publishedByUserId"    uuid,
    "version"              integer NOT NULL DEFAULT 0,
    "createdAt"            timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"            timestamp(3) without time zone NOT NULL,
    CONSTRAINT "report_definitions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "report_definitions_key_check"
        CHECK (length(btrim("key")) > 0),
    -- ARABIC-FIRST IS PART OF THE PRODUCT. A report with only an English name is
    -- dead configuration and the database refuses it rather than letting it
    -- reach a dashboard.
    CONSTRAINT "report_definitions_name_ar_check"
        CHECK (length(btrim("nameAr")) > 0),
    CONSTRAINT "report_definitions_name_en_check"
        CHECK (length(btrim("nameEn")) > 0),
    -- A timezone that is not a zone PostgreSQL knows is not a timezone. The
    -- shape check would accept "Asia/Riyaddah"; this refuses it.
    CONSTRAINT "report_definitions_timezone_check"
        CHECK ("public"."firstelement_valid_timezone"("timezone")),
    -- A published definition says WHEN and WHO, and which body is live.
    CONSTRAINT "report_definitions_published_pair_check"
        CHECK (("publishedAt" IS NULL) = ("publishedByUserId" IS NULL)),
    CONSTRAINT "report_definitions_published_version_check"
        CHECK ("status" <> 'PUBLISHED'
               OR ("publishedVersion" IS NOT NULL AND "publishedAt" IS NOT NULL)),
    -- currentVersion is a pointer into report_versions; it may only be set once
    -- at least one version exists, which the composite FK enforces.
    CONSTRAINT "report_definitions_current_version_check"
        CHECK ("currentVersion" IS NULL OR "currentVersion" >= 1),
    CONSTRAINT "report_definitions_published_version_positive_check"
        CHECK ("publishedVersion" IS NULL OR "publishedVersion" >= 1),
    CONSTRAINT "report_definitions_redaction_policy_check"
        CHECK (length(btrim("redactionPolicy")) > 0),
    CONSTRAINT "report_definitions_retention_class_check"
        CHECK (length(btrim("retentionClass")) > 0),
    -- A permission string that is blank is a permission nobody can hold, and it
    -- would look configured in the Admin UI.
    CONSTRAINT "report_definitions_required_permissions_check"
        CHECK (NOT "public"."firstelement_array_has_blank"("requiredPermissions")),
    CONSTRAINT "report_definitions_version_check"
        CHECK ("version" >= 0)
);

ALTER TABLE ONLY "public"."report_definitions"
    ADD CONSTRAINT "report_definitions_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."report_definitions"
    ADD CONSTRAINT "report_definitions_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."report_definitions"
    ADD CONSTRAINT "report_definitions_published_by_tenant_fkey"
    FOREIGN KEY ("publishedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Composite tenant key: an export may pin this tenant's report version.
CREATE UNIQUE INDEX "report_definitions_id_organizationId_key"
    ON "public"."report_definitions" USING "btree" ("id", "organizationId");

-- Tenant-scoped business key. Including projectId would let `onboarding_v1`
-- exist once per project with no way to name one of them.
CREATE UNIQUE INDEX "report_definitions_organizationId_key_key"
    ON "public"."report_definitions" USING "btree" ("organizationId", "key");

-- The catalogue read: this tenant's reports, newest first, optionally narrowed
-- to a project.
CREATE INDEX "report_definitions_organizationId_status_key_idx"
    ON "public"."report_definitions" USING "btree"
       ("organizationId", "status", "key");

CREATE INDEX "report_definitions_organizationId_projectId_status_idx"
    ON "public"."report_definitions" USING "btree"
       ("organizationId", "projectId", "status");

-- THE "ONE LIVE BODY" INDEX LIVES ON report_versions, NOT HERE. The obvious
-- place for it is the definition, and putting it there is a bug: a definition's
-- key is already unique per tenant, so a partial index on
-- ("organizationId","key") WHERE status='PUBLISHED' could never fire -- there is
-- only ever one row with that key at all. What actually needs enforcing is that
-- one DEFINITION has one PUBLISHED body, and a body is a version. The index is
-- therefore declared on report_versions below, and this comment exists so the
-- redundant version is not "added back" for symmetry.

-- ---------------------------------------------------------------------
-- report_versions -- the immutable body
-- ---------------------------------------------------------------------
-- APPEND-ONLY IN THE MEANINGFUL SENSE. There is no updatedAt, and the only
-- mutation a version may ever undergo is RETIRED + supersession, which is an
-- audited state change rather than an edit: a published number must stay
-- reproducible, so "fix the typo in the measure" is a new version, never an
-- update of the one a report already shipped against.
CREATE TABLE "public"."report_versions" (
    "id"                   uuid NOT NULL,
    "organizationId"       uuid NOT NULL,
    "projectId"            uuid,
    "reportDefinitionId"   uuid NOT NULL,
    "versionNumber"        integer NOT NULL,
    -- ALLOW-LISTS, NOT SQL. Each is a JSON ARRAY of keys the application
    -- resolves against a typed registry; nothing here is ever interpolated.
    "dimensions"           jsonb NOT NULL DEFAULT '[]'::jsonb,
    "measures"             jsonb NOT NULL DEFAULT '[]'::jsonb,
    "filters"              jsonb NOT NULL DEFAULT '{}'::jsonb,
    "defaultSort"          jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- The export column set, pinned. This is what makes a CSV re-downloadable
    -- byte-for-byte the same shape it was.
    "columns"              jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- FR-7.6 / ADR-0007 rule 5: denominator rules are DATA, so "0% of tickets"
    -- and "no tickets" stay distinguishable in every read model.
    "denominatorRules"     jsonb NOT NULL DEFAULT '{}'::jsonb,
    "metricSemantics"      jsonb NOT NULL DEFAULT '{}'::jsonb,
    "state"                "public"."ReportStatus" NOT NULL DEFAULT 'DRAFT',
    "changeNote"           text,
    "publishedAt"          timestamp(3) without time zone,
    "publishedByUserId"    uuid,
    "retiredAt"            timestamp(3) without time zone,
    "supersededByVersionId" uuid,
    "createdByUserId"      uuid NOT NULL,
    -- NO updatedAt. A version body is written once.
    "createdAt"            timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "report_versions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "report_versions_version_number_check"
        CHECK ("versionNumber" >= 1),
    -- THE SHAPE CHECKS THAT MAKE THE ALLOW-LIST REVIEWABLE. An ARRAY here
    -- cannot be a free-form JSON document, and an OBJECT cannot be where a list
    -- is. This is the difference between "a versioned metadata registry" and
    -- "an unbounded dynamic schema".
    CONSTRAINT "report_versions_dimensions_shape_check"
        CHECK (jsonb_typeof("dimensions") = 'array'),
    CONSTRAINT "report_versions_measures_shape_check"
        CHECK (jsonb_typeof("measures") = 'array'),
    CONSTRAINT "report_versions_filters_shape_check"
        CHECK (jsonb_typeof("filters") = 'object'),
    CONSTRAINT "report_versions_default_sort_shape_check"
        CHECK (jsonb_typeof("defaultSort") = 'array'),
    CONSTRAINT "report_versions_columns_shape_check"
        CHECK (jsonb_typeof("columns") = 'array'),
    CONSTRAINT "report_versions_denominator_rules_shape_check"
        CHECK (jsonb_typeof("denominatorRules") = 'object'),
    CONSTRAINT "report_versions_metric_semantics_shape_check"
        CHECK (jsonb_typeof("metricSemantics") = 'object'),
    -- A report with no measure is not a report.
    CONSTRAINT "report_versions_measures_present_check"
        CHECK (jsonb_array_length("measures") >= 1),
    -- Every allow-list entry must be a non-empty string, or "it looks
    -- configured" and "it resolves to nothing" become the same state.
    CONSTRAINT "report_versions_measures_entries_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("measures")),
    CONSTRAINT "report_versions_dimensions_entries_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("dimensions")),
    CONSTRAINT "report_versions_columns_entries_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("columns")),
    -- A sort entry must name a field and a direction, or the sort is a
    -- suggestion the reader cannot act on.
    CONSTRAINT "report_versions_sort_entries_check"
        CHECK ("public"."firstelement_json_list_is_sort"("defaultSort")),
    CONSTRAINT "report_versions_published_pair_check"
        CHECK (("publishedAt" IS NULL) = ("publishedByUserId" IS NULL)),
    -- A PUBLISHED version says when; a DRAFT has never been published.
    CONSTRAINT "report_versions_published_state_check"
        CHECK (("state" = 'DRAFT'::"public"."ReportStatus") = ("publishedAt" IS NULL)),
    -- A RETIRED version names what replaced it. A retirement that does not say
    -- where it went is the rollback problem Phase 6a already recorded.
    CONSTRAINT "report_versions_retired_check"
        CHECK ("state" <> 'RETIRED'::"public"."ReportStatus"
               OR ("retiredAt" IS NOT NULL AND "supersededByVersionId" IS NOT NULL)),
    -- A version cannot supersede itself.
    CONSTRAINT "report_versions_supersede_self_check"
        CHECK ("supersededByVersionId" IS NULL OR "supersededByVersionId" <> "id")
);

ALTER TABLE ONLY "public"."report_versions"
    ADD CONSTRAINT "report_versions_definition_tenant_fkey"
    FOREIGN KEY ("reportDefinitionId", "organizationId")
    REFERENCES "public"."report_definitions"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."report_versions"
    ADD CONSTRAINT "report_versions_author_tenant_fkey"
    FOREIGN KEY ("createdByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."report_versions"
    ADD CONSTRAINT "report_versions_published_by_tenant_fkey"
    FOREIGN KEY ("publishedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Composite tenant key: a version's supersession pointer must stay inside the
-- tenant, and V20261005070000's export jobs need this as an FK target.
CREATE UNIQUE INDEX "report_versions_id_organizationId_key"
    ON "public"."report_versions" USING "btree" ("id", "organizationId");

-- One version number per definition, per tenant.
CREATE UNIQUE INDEX "report_versions_definition_version_key"
    ON "public"."report_versions" USING "btree"
       ("organizationId", "reportDefinitionId", "versionNumber");

-- THE ONE-LIVE-BODY INDEX. Partial, so DRAFT and RETIRED versions coexist
-- freely -- which is the whole reason DRAFT is the first enum value -- and a
-- concurrent double publication loses on this index rather than on application
-- ordering.
CREATE UNIQUE INDEX "report_versions_one_published_per_definition"
    ON "public"."report_versions" USING "btree"
       ("organizationId", "reportDefinitionId")
    WHERE "state" = 'PUBLISHED';

-- "The next version number" and the version timeline.
CREATE INDEX "report_versions_organizationId_definition_version_idx"
    ON "public"."report_versions" USING "btree"
       ("organizationId", "reportDefinitionId", "versionNumber");

-- The composition path: a version, then its successor.
CREATE INDEX "report_versions_organizationId_superseded_idx"
    ON "public"."report_versions" USING "btree"
       ("organizationId", "supersededByVersionId");

-- ---------------------------------------------------------------------
-- document_templates -- versioned export/render templates
-- ---------------------------------------------------------------------
CREATE TABLE "public"."document_templates" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "key"                text NOT NULL,
    "nameAr"             text NOT NULL,
    "nameEn"             text NOT NULL,
    "kind"               text NOT NULL,
    "status"             "public"."DocumentTemplateStatus" NOT NULL DEFAULT 'DRAFT',
    "currentVersion"     integer,
    "publishedVersion"   integer,
    "publishedAt"        timestamp(3) without time zone,
    "publishedByUserId"  uuid,
    "locale"             "public"."Locale" NOT NULL,
    -- Same real-zone validation as report_definitions, so a DOCX template and
    -- the report it renders cannot disagree about what a date means.
    "timezone"           text NOT NULL,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "document_templates_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "document_templates_key_check"
        CHECK (length(btrim("key")) > 0),
    CONSTRAINT "document_templates_name_ar_check"
        CHECK (length(btrim("nameAr")) > 0),
    CONSTRAINT "document_templates_name_en_check"
        CHECK (length(btrim("nameEn")) > 0),
    -- A template is DOCX or PDF. It is a CLOSED set because it is what the
    -- renderer supports, and a template of an unsupported kind is a job that
    -- fails after it has already been paid for.
    CONSTRAINT "document_templates_kind_check"
        CHECK ("kind" IN ('DOCX', 'PDF')),
    CONSTRAINT "document_templates_timezone_check"
        CHECK ("public"."firstelement_valid_timezone"("timezone")),
    CONSTRAINT "document_templates_published_pair_check"
        CHECK (("publishedAt" IS NULL) = ("publishedByUserId" IS NULL)),
    CONSTRAINT "document_templates_published_state_check"
        CHECK ("status" <> 'PUBLISHED'::"public"."DocumentTemplateStatus"
               OR ("publishedVersion" IS NOT NULL AND "publishedAt" IS NOT NULL)),
    CONSTRAINT "document_templates_version_pointer_check"
        CHECK ("currentVersion" IS NULL OR "currentVersion" >= 1),
    CONSTRAINT "document_templates_version_check"
        CHECK ("version" >= 0)
);

ALTER TABLE ONLY "public"."document_templates"
    ADD CONSTRAINT "document_templates_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."document_templates"
    ADD CONSTRAINT "document_templates_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."document_templates"
    ADD CONSTRAINT "document_templates_published_by_tenant_fkey"
    FOREIGN KEY ("publishedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE UNIQUE INDEX "document_templates_id_organizationId_key"
    ON "public"."document_templates" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "document_templates_organizationId_key_kind_key"
    ON "public"."document_templates" USING "btree"
       ("organizationId", "key", "kind");

CREATE INDEX "document_templates_organizationId_status_idx"
    ON "public"."document_templates" USING "btree"
       ("organizationId", "status");

-- Like report_versions, the one-live-body index belongs on the VERSION table:
-- the definition's (key, kind) is already unique per tenant, so a partial index
-- there could never fire.

-- ---------------------------------------------------------------------
-- document_template_versions -- APPEND-ONLY once written
-- ---------------------------------------------------------------------
CREATE TABLE "public"."document_template_versions" (
    "id"                   uuid NOT NULL,
    "organizationId"       uuid NOT NULL,
    "projectId"            uuid,
    "documentTemplateId"   uuid NOT NULL,
    "versionNumber"        integer NOT NULL,
    "locale"               "public"."Locale" NOT NULL,
    "storageKey"           text NOT NULL,
    "contentHash"          text NOT NULL,
    "placeholderSchema"    jsonb NOT NULL DEFAULT '{}'::jsonb,
    "state"                "public"."DocumentTemplateStatus" NOT NULL DEFAULT 'DRAFT',
    "changeNote"           text,
    "publishedAt"          timestamp(3) without time zone,
    "publishedByUserId"    uuid,
    "retiredAt"            timestamp(3) without time zone,
    "supersededByVersionId" uuid,
    "createdByUserId"      uuid NOT NULL,
    "createdAt"            timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "document_template_versions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "document_template_versions_version_number_check"
        CHECK ("versionNumber" >= 1),
    -- `storageKey` is an OPAQUE object key. Never a customer name, a phone
    -- number, a ticket number or a presigned URL: a storage key that discloses
    -- a patient name is a data-protection incident with no boundary.
    CONSTRAINT "document_template_versions_storage_key_check"
        CHECK (length(btrim("storageKey")) > 0
               AND "storageKey" NOT LIKE '%?%'
               AND "storageKey" NOT LIKE '%://%'),
    -- A content hash is what makes "which template produced this export"
    -- answerable months later. A blank one is not a hash.
    CONSTRAINT "document_template_versions_content_hash_check"
        CHECK (length(btrim("contentHash")) > 0),
    CONSTRAINT "document_template_versions_placeholder_shape_check"
        CHECK (jsonb_typeof("placeholderSchema") = 'object'),
    -- Placeholder names are an allow-list too: a template that interpolates
    -- "{{conversation.customerName}}" must at least be a list of non-empty
    -- names, so FR-10.6 redaction can be applied to the RENDER rather than
    -- guessed at afterwards.
    CONSTRAINT "document_template_versions_placeholder_entries_check"
        CHECK ("public"."firstelement_json_list_is_string_array"
               (COALESCE("placeholderSchema" -> 'placeholders', '[]'::jsonb))),
    CONSTRAINT "document_template_versions_published_pair_check"
        CHECK (("publishedAt" IS NULL) = ("publishedByUserId" IS NULL)),
    CONSTRAINT "document_template_versions_published_state_check"
        CHECK (("state" = 'DRAFT'::"public"."DocumentTemplateStatus")
               = ("publishedAt" IS NULL)),
    CONSTRAINT "document_template_versions_retired_check"
        CHECK ("state" <> 'RETIRED'::"public"."DocumentTemplateStatus"
               OR ("retiredAt" IS NOT NULL AND "supersededByVersionId" IS NOT NULL)),
    CONSTRAINT "document_template_versions_supersede_self_check"
        CHECK ("supersededByVersionId" IS NULL OR "supersededByVersionId" <> "id")
);

ALTER TABLE ONLY "public"."document_template_versions"
    ADD CONSTRAINT "document_template_versions_template_tenant_fkey"
    FOREIGN KEY ("documentTemplateId", "organizationId")
    REFERENCES "public"."document_templates"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."document_template_versions"
    ADD CONSTRAINT "document_template_versions_author_tenant_fkey"
    FOREIGN KEY ("createdByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."document_template_versions"
    ADD CONSTRAINT "document_template_versions_published_by_tenant_fkey"
    FOREIGN KEY ("publishedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE UNIQUE INDEX "document_template_versions_id_organizationId_key"
    ON "public"."document_template_versions" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "document_template_versions_template_version_key"
    ON "public"."document_template_versions" USING "btree"
       ("organizationId", "documentTemplateId", "versionNumber");

CREATE UNIQUE INDEX "document_template_versions_one_published_per_template"
    ON "public"."document_template_versions" USING "btree"
       ("organizationId", "documentTemplateId")
    WHERE "state" = 'PUBLISHED';

CREATE INDEX "document_template_versions_organizationId_template_idx"
    ON "public"."document_template_versions" USING "btree"
       ("organizationId", "documentTemplateId", "versionNumber");

-- ---------------------------------------------------------------------
-- Post-condition guard.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing         text;
    ok_tables       text[] := ARRAY['report_definitions', 'report_versions',
                                   'document_templates', 'document_template_versions'];
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'report_definitions_id_organizationId_key',
               'report_definitions_organizationId_key_key',
               'report_definitions_organizationId_status_key_idx',
               'report_definitions_organizationId_projectId_status_idx',
               'report_versions_id_organizationId_key',
               'report_versions_definition_version_key',
               'report_versions_one_published_per_definition',
               'report_versions_organizationId_definition_version_idx',
               'report_versions_organizationId_superseded_idx',
               'document_templates_id_organizationId_key',
               'document_templates_organizationId_key_kind_key',
               'document_templates_organizationId_status_idx',
               'document_template_versions_id_organizationId_key',
               'document_template_versions_template_version_key',
               'document_template_versions_one_published_per_template',
               'document_template_versions_organizationId_template_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005060000 failed: missing indexes: %', missing;
    END IF;

    SELECT string_agg(t, ', ')
      INTO missing
      FROM unnest(ok_tables) AS t
     WHERE to_regclass('public.' || quote_ident(t)) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005060000 failed: missing tables: %', missing;
    END IF;

    -- 1. APPEND-ONLY BOUNDARY, stated as a map rather than one flag:
    --    report_versions and document_template_versions carry no updatedAt; the
    --    two definition tables are mutable so a DRAFT can be renamed.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'updatedAt'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN ('report_versions', 'document_template_versions');

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005060000 failed: a version body is written once and must not have '
            'updatedAt: %', missing;
    END IF;

    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY['report_definitions', 'document_templates']) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = x
                         AND a.attname = 'updatedAt'
                         AND a.attnum > 0 AND NOT a.attisdropped);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005060000 failed: a definition is mutable and must have updatedAt: %',
            missing;
    END IF;

    -- 2. FR-1.2 on every table here.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'organizationId'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND NOT a.attnotnull;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005060000 failed: organizationId must be NOT NULL (FR-1.2) on: %', missing;
    END IF;

    -- 3. V6 contract: no timestamptz.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.atttypid = 'timestamptz'::regtype
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables));

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005060000 failed: timestamptz column in: %', missing;
    END IF;

    -- 4. Every UTC-writing default must say UTC.
    SELECT string_agg(c.relname || '.' || a.attname, ', ')
      INTO missing
      FROM pg_attrdef d
        JOIN pg_class c ON c.oid = d.adrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.adnum
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
       AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%';

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005060000 failed: a default uses a session-local CURRENT_TIMESTAMP: %', missing;
    END IF;

    -- 5. Enum value lists ARE the contract. DRAFT comes FIRST in both enums:
    --    the Phase-6a throwaway rehearsal proved the design deadlocks without it.
    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'ReportStatus')
       IS DISTINCT FROM 'DRAFT,PUBLISHED,RETIRED' THEN
        RAISE EXCEPTION 'V20261005060000 failed: public.ReportStatus value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'DocumentTemplateStatus')
       IS DISTINCT FROM 'DRAFT,PUBLISHED,RETIRED' THEN
        RAISE EXCEPTION
            'V20261005060000 failed: public.DocumentTemplateStatus value list drifted.';
    END IF;

    -- 6. The four composite tenant keys must be UNIQUE INDEXES, not UNIQUE
    --    CONSTRAINTS: V4's unique_constraints = 0 invariant survives only if
    --    uniqueness is carried by indexes.
    IF (SELECT count(*) FROM pg_index i
          JOIN pg_class c ON c.oid = i.indexrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname IN ('report_definitions_id_organizationId_key',
                             'report_versions_id_organizationId_key',
                             'document_templates_id_organizationId_key',
                             'document_template_versions_id_organizationId_key')
           AND i.indisunique) <> 4 THEN
        RAISE EXCEPTION
            'V20261005060000 failed: the four composite tenant keys must be UNIQUE indexes.';
    END IF;

    -- 7. THE PARTIAL "ONE LIVE BODY" INDEXES MUST REALLY BE PARTIAL, AND MUST BE ON
    --    THE VERSION TABLES. A unique index without the WHERE clause would forbid
    --    two DRAFT versions of one report, which is precisely the deadlock the
    --    DRAFT state exists to prevent, and it would fail only at the second
    --    version. On the DEFINITION it would be worse than useless: the key is
    --    already unique per tenant, so it could never fire.
    IF (SELECT count(*) FROM pg_index i
          JOIN pg_class c ON c.oid = i.indexrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_class t ON t.oid = i.indrelid
          JOIN pg_namespace tn ON tn.oid = t.relnamespace
         WHERE n.nspname = 'public' AND tn.nspname = 'public'
           AND c.relname IN ('report_versions_one_published_per_definition',
                             'document_template_versions_one_published_per_template')
           AND t.relname IN ('report_versions', 'document_template_versions')
           AND i.indisunique AND i.indpred IS NOT NULL) <> 2 THEN
        RAISE EXCEPTION
            'V20261005060000 failed: both "one live body" indexes must be partial UNIQUE '
            'indexes on their VERSION table, otherwise a second DRAFT version cannot be '
            'authored.';
    END IF;

    -- 10. THE HELPER FUNCTIONS MUST EXIST AND MUST STILL DISCRIMINATE. A CHECK
    --     that silently degraded to a regex (or a function dropped with the
    --     column) would let a nonexistent zone or a blank permission back in,
    --     which is the exact failure these functions exist to prevent.
    IF to_regprocedure('public.firstelement_valid_timezone(text)') IS NULL
       OR to_regprocedure('public.firstelement_array_has_blank(anyarray)') IS NULL
       OR to_regprocedure('public.firstelement_json_list_is_string_array(jsonb)') IS NULL
       OR to_regprocedure('public.firstelement_json_list_is_sort(jsonb)') IS NULL THEN
        RAISE EXCEPTION
            'V20261005060000 failed: one of the four Phase-7-10a validator functions is '
            'missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p
                     JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public'
                      AND p.proname = 'firstelement_valid_timezone'
                      AND p.provolatile <> 'i') THEN
        RAISE EXCEPTION
            'V20261005060000 failed: public.firstelement_valid_timezone must be STABLE (it '
            'reads pg_timezone_names), not IMMUTABLE.';
    END IF;

    IF (SELECT public.firstelement_valid_timezone('Asia/Riyadh')) IS NOT TRUE
       OR (SELECT public.firstelement_valid_timezone('Asia/Riyaddah')) IS NOT FALSE
       OR (SELECT public.firstelement_array_has_blank(ARRAY['a', ' '])) IS NOT TRUE
       OR (SELECT public.firstelement_array_has_blank(ARRAY['a', 'b'])) IS NOT FALSE
       OR (SELECT public.firstelement_json_list_is_string_array('["a",""]'::jsonb)) IS NOT FALSE
       OR (SELECT public.firstelement_json_list_is_string_array('["a","b"]'::jsonb)) IS NOT TRUE
       OR (SELECT public.firstelement_json_list_is_sort('[{"field":"x","direction":"up"}]'::jsonb)) IS NOT FALSE
       OR (SELECT public.firstelement_json_list_is_sort('[{"field":"x","direction":"DESC"}]'::jsonb)) IS NOT TRUE THEN
        RAISE EXCEPTION
            'V20261005060000 failed: a Phase-7-10a validator function does not '
            'discriminate its bad input from its good input.';
    END IF;

    -- 11. Truncation detection: every index on these tables must be a name this
    --    file declared. A name over 63 bytes is silently truncated and can never
    --    match; a legitimate 63-byte name is NOT flagged (the Phase-6a rehearsal
    --    proved `length = 63` is the wrong test).
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'document_template_versions'
                  AND indexname NOT IN (
                      'document_template_versions_pkey',
                      'document_template_versions_id_organizationId_key',
                      'document_template_versions_template_version_key',
                      'document_template_versions_one_published_per_template',
                      'document_template_versions_organizationId_template_idx')) THEN
        RAISE EXCEPTION
            'V20261005060000 failed: an unexpected index exists on document_template_versions '
            '-- most likely a name longer than 63 bytes was silently truncated.';
    END IF;
END
$$;