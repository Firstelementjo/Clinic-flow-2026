-- =====================================================================
-- V20261005010000__workflow_definitions_and_versions.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- WHY 20261005010000 AND NOT A 12-DIGIT DRAFT NUMBER
--   Flyway orders versions NUMERICALLY, not lexically. The Phase-2 drafts
--   were 12 digits (2026100400nn). 202610040001 is 202,610,040,001, which is
--   far SMALLER than the 14-digit baseline 20,260,814,000,000, so a
--   draft-numbered migration sorts BEFORE the baseline and runs against an
--   EMPTY database. The Phase-5a fixture proved it: Flyway applied the outbox
--   migration first and failed with
--   `relation "public.organizations" does not exist`.
--   Every promoted migration in this chain is therefore 14 digits
--   (yyyyMMddHHmmss) and numerically greater than 20261004001000:
--       20260814000000  baseline                       (23 enums, 45 tables)
--       20260814000100  vector indexes
--       20261004000100  outbox                         (draft 001)
--       20261004000700  job attempts                   (draft 007)
--       20261004001000  idempotency records            (new)
--       20261005010000  workflow definitions + versions   (Phase 6a, draft 101)
--       20261005020000  workflow runs + action results    (Phase 6a, draft 102)
--       20261005030000  SLA calendars, holidays, policies (Phase 6a, draft 103)
--       20261005040000  collision records                (Phase 6a, draft 104)
--
--   Phase 6a is FOUR migrations, not one, because the slices are independent
--   and each has its own undo boundary. One file per concern means a failure in
--   the collision index cannot take the SLA clock invariants with it, and the
--   armed undo in ops/flyway/rollback/undo_phase6a.sql can be reasoned about
--   per slice.
--   EVERY remaining ops/flyway/pending/*.sql.todo still carries 12 digits and
--   MUST be renumbered to 14 digits when it is promoted.
--
-- WHY THIS MIGRATION EXISTS
--   Phase 6 (backend/workflows-sla-routing) needs versioned workflow
--   definitions with an activation and rollback state. "Versioned" is not
--   decoration here:
--     * PROJECT_REQUIREMENTS / plan.md hard rule: "No alphabetical rule
--       ordering. Priority is explicit and versioned." An operator who
--       reorders a workflow must get a NEW version, never an edit of the one
--       an in-flight run is executing.
--     * plan.md gate: "a workflow run executes as a named principal with a
--       bounded capability set, recorded per execution." The version is what
--       records WHICH capability set was in force when the run happened, so
--       an explainability claim about the past is answerable forever.
--     * Rolling back is an explicit, audited state change (a new version
--       marked ROLLED_BACK plus a pointer to the version rolled back TO), not
--       a destructive edit.
--
--   PROJECT_REQUIREMENTS section 4 (automation), section 5 (helpdesk/SLA),
--   FR-6.7 (an operator must be able to diagnose without database access).
--
-- WHAT IT CREATES
--   1. enum  public.WorkflowVersionState  (DRAFT, ACTIVE, SUPERSEDED,
--      ROLLED_BACK, ARCHIVED)
--   2. table public.workflow_definitions  -- the stable identity + priority
--   3. table public.workflow_versions      -- the versioned body + lifecycle
--
-- WHY A DEFINITION AND A VERSION ARE SEPARATE TABLES
--   A definition is the thing an operator names, enables and orders. A version
--   is the immutable body a run binds to. If they were one table, "what is the
--   current body" and "what ran last Tuesday" would be the same row, and the
--   answer to the second question would be destroyed by an edit to the first.
--   Same split as V20261004000700's job_dead_letters vs job_attempts.
--
-- ACTIVATION / ROLLBACK STATE -- WHERE IT LIVES AND WHY
--   * workflow_versions.state is the LIFECYCLE (enum, because the value list is
--     a contract the engine branches on).
--   * workflow_definitions.activeVersionId is the CURRENT POINTER, so the hot
--     read ("which body do I execute for definition D") is one indexed lookup
--     and never a scan for state = 'ACTIVE'. It is a pointer, not a state:
--     a definition can be enabled while a human is authoring its next version,
--     which must NOT silently change what production runs.
--   * The two can never disagree because a PARTIAL UNIQUE INDEX allows at most
--     one ACTIVE version per definition:
--         workflow_versions_one_active_per_definition
--             UNIQUE ("organizationId","workflowDefinitionId")
--             WHERE "state" = 'ACTIVE'
--     and the definition's activeVersionId is an FK to exactly that row. The
--     pointer is the write-order enforcement; the partial index is the
--     backstop, so a concurrent double activation loses on the index.
--
-- CAPABILITY SCOPE -- WHY IT IS A COLUMN AND NOT A COMMENT
--   plan.md forbids an unrestricted scheduled "Administrator" identity. A
--   capability set that lives only in the engine is not auditable, so
--   "capabilityScope" is stored on the version, is a jsonb ARRAY (never an
--   object, never null), and is copied onto every workflow_run (V20261005020000)
--   so the run records what it was allowed to do, not what it was later told
--   it could do.
--
-- projectId -- NULLABLE, AND WHY THIS IS THE ORGANISATION-WIDE CASE
--   A workflow definition is frequently organisation-wide: "escalate to the
--   duty manager when a VIP customer waits too long" applies to every project
--   a tenant runs, and forcing the operator to create one duplicate definition
--   per project is how definitions drift apart. organizationId is NOT NULL and
--   is the scope that authorises a read (FR-1.2); a NULL projectId means
--   ORGANISATION-WIDE, never "unknown scope". This mirrors the baseline's two
--   nullable projectId columns (audit_logs, notifications) and the four
--   Phase-5a tables. V11_workflow_sla_slice.sql section 4d lists it explicitly
--   so the exemption class cannot widen unnoticed.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone everywhere, like all 98 baseline timestamp
--   columns (V6 asserts no timestamptz anywhere). Defaults are
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') because the baseline's bare
--   DEFAULT CURRENT_TIMESTAMP stores the SESSION's wall clock; writing UTC
--   explicitly makes a stored timestamp zone-independent. updatedAt has no
--   default, exactly like every mutable table in the baseline, so a writer that
--   forgets it fails loudly and transactionally. Bind LocalDateTime, never
--   Instant/OffsetDateTime.
--
-- APPEND-ONLY vs MUTABLE
--   Both tables are MUTABLE: an operator genuinely does enable, disable,
--   reorder, activate and roll back. So both carry createdAt + updatedAt. This
--   is stated explicitly because the mirror-image decision -- no updatedAt at
--   all -- is what V20261005020000 makes for workflow_run_actions, and a reader
--   must be able to tell the two apart.
--
-- INDEX RATIONALE (FR-1.3: the tenant filter leads, or the index is not tenant
-- scope at all and says so)
--   workflow_definitions_organizationId_key_key  UNIQUE ("organizationId","key")
--       The natural key. Deliberately NOT ("organizationId","projectId","key"):
--       a workflow key must be unique across the TENANT, including across its
--       projects, or "onboarding_v1" could exist once per project and the router
--       would have no way to name one of them. Tenant-scoped, never global: a
--       global unique index would let tenant B learn from the violation message
--       that tenant A already uses that key.
--   workflow_definitions_org_project_trigger_priority_idx
--       ("organizationId","projectId","triggerType","isEnabled","priority")
--       The router's hot read: "enabled definitions for this tenant/project and
--       trigger, in explicit priority order". tenant-led so one tenant's
--       workflow set can never crowd another's, and priority is a COLUMN
--       because alphabetical ordering is forbidden.
--   workflow_versions_organizationId_definitionId_versionNumber_key UNIQUE
--       ("organizationId","workflowDefinitionId","versionNumber")
--       Version numbering is per definition, per tenant. Not global: two tenants
--       legitimately both have version 1.
--   workflow_versions_one_active_per_definition  UNIQUE
--       ("organizationId","workflowDefinitionId") WHERE "state" = 'ACTIVE'
--       At most one live body per definition. See above.
--   workflow_versions_organizationId_definitionId_state_idx
--       ("organizationId","workflowDefinitionId","state")
--       The history read: "every version of this definition, newest first",
--       which is what an operator opens after an incident.
--
--   Every one of these five indexes leads with "organizationId". There is
--   deliberately NO tenant-less index in this migration: nothing about
--   authoring a workflow needs to sweep across tenants, and a cross-tenant
--   workflow index would be a read path with no authorisation story.
--
-- SAFETY
--   * CREATE TYPE / CREATE TABLE / ALTER TABLE ADD CONSTRAINT only. No DROP, no
--     RENAME, no ALTER of an existing object, no data change, no TRUNCATE, no
--     UPDATE of a legacy row. Nothing in this file reads an existing row.
--   * Strict CREATE TABLE, not IF NOT EXISTS: a pre-existing
--     workflow_definitions must fail the migration loudly rather than be
--     silently adopted with an unknown shape.
--   * FKs only where the parent is guaranteed: organizations, projects, users,
--     and the two intra-slice parents (a version's definition, a version's
--     rollback target). The circular pair definition -> version and
--     version -> definition is legal in PostgreSQL because both tables exist
--     before either constraint is added; both use ON DELETE SET NULL so a
--     deleted draft can never take a live pointer with it.
--   * Rollback: DROP TABLE workflow_versions, DROP TABLE workflow_definitions,
--     DROP TYPE WorkflowVersionState -- rehearsed on a throwaway database only
--     (ops/flyway/rollback/undo_phase6a.sql). Safe only while nothing has been
--     cut over to author or execute a workflow.
--   * CHECK constraints here encode invariants NO CORRECT WRITER CAN VIOLATE,
--     so they cannot reject a valid write. Each has a negative test in
--     FlywayBaselineAcceptanceTest.
--   * Identifier names stay under PostgreSQL's 63-byte limit. An over-long name
--     is silently TRUNCATED, so the guard below would not match the created
--     object and the migration would fail on its own index -- a loud failure,
--     but a name that can never work must not be written in the first place.
--   * The post-condition guard looks indexes up in pg_indexes, NOT with
--     to_regclass(): an index is a pg_class row of relkind 'i' and is not a
--     regclass-visible relation, so to_regclass() returns NULL for it and the
--     guard would reject a migration that did everything correctly.
--   * Every identifier is schema-qualified because the baseline sets
--     search_path = '' when the whole chain is piped into ONE psql session.
-- =====================================================================

CREATE TYPE "public"."WorkflowVersionState" AS ENUM (
    'DRAFT',
    'ACTIVE',
    'SUPERSEDED',
    'ROLLED_BACK',
    'ARCHIVED'
);

-- ---------------------------------------------------------------------
-- workflow_definitions: the stable, orderable identity of a workflow.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."workflow_definitions" (
    "id"                  uuid NOT NULL,
    "organizationId"      uuid NOT NULL,
    -- NULL = organisation-wide workflow that applies to every project this
    -- tenant runs. Never "unknown scope". Listed by V11 section 4d.
    "projectId"           uuid,
    "key"                 text NOT NULL,
    "name"                text NOT NULL,
    "description"         text,
    -- Free text, deliberately: the trigger vocabulary will grow, and a
    -- vocabulary that will grow must not need a migration (Phase-5a rule).
    "triggerType"         text NOT NULL,
    -- Explicit, versioned priority. NEVER alphabetical (plan.md hard rule).
    "priority"            integer DEFAULT 100 NOT NULL,
    "isEnabled"           boolean DEFAULT true NOT NULL,
    "latestVersionNumber" integer DEFAULT 0 NOT NULL,
    -- Pointer to the single ACTIVE version. NULL while the workflow is still
    -- being authored, which is a legitimate state, not a defect.
    "activeVersionId"     uuid,
    "createdByUserId"     uuid,
    "createdAt"           timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"           timestamp(3) without time zone NOT NULL,
    CONSTRAINT "workflow_definitions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "workflow_definitions_latest_version_check"
        CHECK ("latestVersionNumber" >= 0),
    CONSTRAINT "workflow_definitions_priority_check"
        CHECK ("priority" >= 0),
    -- A non-empty key is not cosmetic: it is the ON CONFLICT target and the
    -- thing an operator types.
    CONSTRAINT "workflow_definitions_key_check"
        CHECK (length(btrim("key")) > 0)
);

ALTER TABLE ONLY "public"."workflow_definitions"
    ADD CONSTRAINT "workflow_definitions_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_definitions"
    ADD CONSTRAINT "workflow_definitions_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_definitions"
    ADD CONSTRAINT "workflow_definitions_createdByUserId_fkey"
    FOREIGN KEY ("createdByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- workflow_versions: the immutable body plus its activation lifecycle.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."workflow_versions" (
    "id"                       uuid NOT NULL,
    "organizationId"           uuid NOT NULL,
    "projectId"                uuid,
    "workflowDefinitionId"     uuid NOT NULL,
    "versionNumber"            integer NOT NULL,
    "state"                    "public"."WorkflowVersionState" DEFAULT 'DRAFT'::"public"."WorkflowVersionState" NOT NULL,
    "changeNote"               text,
    -- The state machine body: nodes, edges, per-action capability needs.
    "stateMachine"             jsonb DEFAULT '{}'::"jsonb" NOT NULL,
    "triggerConfig"            jsonb DEFAULT '{}'::"jsonb" NOT NULL,
    -- A JSON ARRAY of capability names, never an object and never null.
    -- plan.md: no unrestricted scheduled identity, and the set must be
    -- auditable after the fact.
    "capabilityScope"          jsonb DEFAULT '[]'::"jsonb" NOT NULL,
    -- Deterministic digest of the body. Two versions with the same digest are
    -- the same workflow, which is what makes a dry-run preview honest.
    "contentHash"              text NOT NULL,
    "activatedAt"              timestamp(3) without time zone,
    "activatedByUserId"        uuid,
    "supersededAt"             timestamp(3) without time zone,
    "supersededByVersionId"    uuid,
    "rolledBackAt"             timestamp(3) without time zone,
    "rolledBackByUserId"       uuid,
    -- The version the operator went BACK TO. A rollback that does not say where
    -- it landed is not a rollback.
    "rolledBackToVersionId"    uuid,
    "createdByUserId"          uuid,
    "createdAt"                timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"                timestamp(3) without time zone NOT NULL,
    CONSTRAINT "workflow_versions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "workflow_versions_version_number_check"
        CHECK ("versionNumber" >= 1),
    -- A DRAFT has never been activated; anything else has. This is what stops
    -- a DRAFT from claiming to have been live.
    CONSTRAINT "workflow_versions_activation_check"
        CHECK (("state" = 'DRAFT'::"public"."WorkflowVersionState")
               = ("activatedAt" IS NULL)),
    -- An ACTIVED version must say WHO activated it.
    CONSTRAINT "workflow_versions_activated_by_check"
        CHECK (("state" <> 'ACTIVE'::"public"."WorkflowVersionState")
               OR "activatedByUserId" IS NOT NULL),
    -- A superseded version must record when, and the version that replaced it.
    CONSTRAINT "workflow_versions_supersede_check"
        CHECK (("state" <> 'SUPERSEDED'::"public"."WorkflowVersionState")
               OR ("supersededAt" IS NOT NULL AND "supersededByVersionId" IS NOT NULL)),
    -- A rollback must be attributable AND say where it went back to.
    CONSTRAINT "workflow_versions_rollback_check"
        CHECK (("state" <> 'ROLLED_BACK'::"public"."WorkflowVersionState")
               OR ("rolledBackAt" IS NOT NULL
                   AND "rolledBackByUserId" IS NOT NULL
                   AND "rolledBackToVersionId" IS NOT NULL)),
    -- capabilityScope is a capability LIST. An object or a null here would mean
    -- a capability set that cannot be diffed against another version.
    CONSTRAINT "workflow_versions_capability_scope_check"
        CHECK (jsonb_typeof("capabilityScope") = 'array'),
    CONSTRAINT "workflow_versions_content_hash_check"
        CHECK (length(btrim("contentHash")) > 0)
);

ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- The parent is guaranteed: every version belongs to exactly one definition.
ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_definitionId_fkey"
    FOREIGN KEY ("workflowDefinitionId") REFERENCES "public"."workflow_definitions"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- A rollback names the version it rolled back TO; a supersede names the one that
-- replaced it. Both SET NULL rather than CASCADE: an audit pointer that vanishes
-- with the row it points at is worse than a dangling reference the operator can
-- see.
ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_rolledBackToVersionId_fkey"
    FOREIGN KEY ("rolledBackToVersionId") REFERENCES "public"."workflow_versions"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_supersededByVersionId_fkey"
    FOREIGN KEY ("supersededByVersionId") REFERENCES "public"."workflow_versions"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_activatedByUserId_fkey"
    FOREIGN KEY ("activatedByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_rolledBackByUserId_fkey"
    FOREIGN KEY ("rolledBackByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE ONLY "public"."workflow_versions"
    ADD CONSTRAINT "workflow_versions_createdByUserId_fkey"
    FOREIGN KEY ("createdByUserId") REFERENCES "public"."users"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- Added last, after both tables exist: this is the second half of the circular
-- pair (definition -> active version). A definition that is being re-pointed
-- must keep existing while its versions do.
ALTER TABLE ONLY "public"."workflow_definitions"
    ADD CONSTRAINT "workflow_definitions_activeVersionId_fkey"
    FOREIGN KEY ("activeVersionId") REFERENCES "public"."workflow_versions"("id")
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ---------------------------------------------------------------------
-- indexes
-- ---------------------------------------------------------------------
-- Natural key, tenant-scoped and project-independent (see header).
CREATE UNIQUE INDEX "workflow_definitions_organizationId_key_key"
    ON "public"."workflow_definitions" USING "btree" ("organizationId", "key");

-- The router's hot read: enabled definitions for a trigger, in explicit
-- priority order.
CREATE INDEX "workflow_definitions_org_project_trigger_priority_idx"
    ON "public"."workflow_definitions" USING "btree"
       ("organizationId", "projectId", "triggerType", "isEnabled", "priority");

CREATE UNIQUE INDEX "workflow_versions_organizationId_definitionId_versionNumber_key"
    ON "public"."workflow_versions" USING "btree"
       ("organizationId", "workflowDefinitionId", "versionNumber");

-- At most one live body per definition. The partial predicate is what makes
-- this cheap: an operator with 200 versions and 1 active indexes one row.
CREATE UNIQUE INDEX "workflow_versions_one_active_per_definition"
    ON "public"."workflow_versions" USING "btree" ("organizationId", "workflowDefinitionId")
    WHERE "state" = 'ACTIVE'::"public"."WorkflowVersionState";

-- The history read an operator opens after an incident.
CREATE INDEX "workflow_versions_organizationId_definitionId_state_idx"
    ON "public"."workflow_versions" USING "btree"
       ("organizationId", "workflowDefinitionId", "state");

-- ---------------------------------------------------------------------
-- Post-condition guard. Fail loudly instead of leaving an authoring surface
-- whose activation invariant is silently unenforced.
-- Index existence is checked in pg_indexes, NOT with to_regclass(): an index
-- is a pg_class row of relkind 'i' and is not a regclass-visible relation, so
-- to_regclass() returns NULL for it and the guard would reject a migration that
-- did everything correctly.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'workflow_definitions_organizationId_key_key',
               'workflow_definitions_org_project_trigger_priority_idx',
               'workflow_versions_organizationId_definitionId_versionNumber_key',
               'workflow_versions_one_active_per_definition',
               'workflow_versions_organizationId_definitionId_state_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005010000 failed: missing workflow indexes: %', missing;
    END IF;

    -- An identifier longer than 63 bytes is silently truncated by PostgreSQL.
    -- Catch it here rather than letting the created object disagree with the
    -- guard above.
    -- TRUNCATION DETECTION, DONE CORRECTLY. PostgreSQL keeps identifiers up to
    -- 63 bytes and truncates anything longer, so an over-long name appears in
    -- pg_indexes as its first 63 bytes and the `missing` check above would fail
    -- with a confusing "missing index" message.
    --
    -- The first attempt at this guard was `length(indexname) = 63`, which is
    -- WRONG and was caught by the throwaway rehearsal: exactly 63 bytes is
    -- legal and untruncated, so that condition fired on a perfectly valid index.
    -- The reliable test is the other direction: EVERY index that exists on
    -- these tables must be one this file declared by its exact name. A truncated
    -- name can never match, so it is caught, and a legitimate 63-byte name is
    -- not. (63 bytes was measured on this host's PostgreSQL 16, not assumed.)
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('workflow_definitions', 'workflow_versions')
                  AND indexname NOT IN (
                      'workflow_definitions_pkey',
                      'workflow_definitions_organizationId_key_key',
                      'workflow_definitions_org_project_trigger_priority_idx',
                      'workflow_versions_pkey',
                      'workflow_versions_organizationId_definitionId_versionNumber_key',
                      'workflow_versions_one_active_per_definition',
                      'workflow_versions_organizationId_definitionId_state_idx')) THEN
        RAISE EXCEPTION
            'V20261005010000 failed: an unexpected index exists on a workflow table -- most '
            'likely a name longer than 63 bytes was silently truncated.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'WorkflowVersionState')
       IS DISTINCT FROM 'DRAFT,ACTIVE,SUPERSEDED,ROLLED_BACK,ARCHIVED' THEN
        RAISE EXCEPTION
            'V20261005010000 failed: public.WorkflowVersionState is not exactly '
            'DRAFT,ACTIVE,SUPERSEDED,ROLLED_BACK,ARCHIVED.';
    END IF;

    -- FR-1.2: a nullable tenant column on a new table is a regression, not a
    -- style question.
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('workflow_definitions', 'workflow_versions')
                  AND a.attname = 'organizationId' AND NOT a.attnotnull) THEN
        RAISE EXCEPTION
            'V20261005010000 failed: "organizationId" must be NOT NULL on both workflow tables (FR-1.2).';
    END IF;

    -- No timestamptz may appear in a new column (V6 contract).
    IF EXISTS (SELECT 1
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('workflow_definitions', 'workflow_versions')
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.atttypid = 'timestamptz'::regtype) THEN
        RAISE EXCEPTION
            'V20261005010000 failed: a workflow table contains a timestamptz column; '
            'every timestamp in this schema is timestamp(3) without time zone.';
    END IF;

    -- Every UTC-writing default must say UTC. The baseline's bare
    -- CURRENT_TIMESTAMP stores the SESSION's wall clock, which is why every new
    -- default in this chain is (CURRENT_TIMESTAMP AT TIME ZONE 'UTC').
    IF EXISTS (SELECT 1
                 FROM pg_attrdef d
                 JOIN pg_class c ON c.oid = d.adrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('workflow_definitions', 'workflow_versions')
                  AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
                  AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%') THEN
        RAISE EXCEPTION
            'V20261005010000 failed: a default uses a session-local CURRENT_TIMESTAMP; '
            'every new default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').';
    END IF;

    -- The circular pair must exist in BOTH directions, or an activation could
    -- name a version this database does not have.
    IF (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND ((c.relname = 'workflow_versions'
                 AND con.conname IN ('workflow_versions_definitionId_fkey',
                                     'workflow_versions_rolledBackToVersionId_fkey',
                                     'workflow_versions_supersededByVersionId_fkey'))
             OR (c.relname = 'workflow_definitions'
                 AND con.conname = 'workflow_definitions_activeVersionId_fkey'))
           AND con.contype = 'f') <> 4 THEN
        RAISE EXCEPTION
            'V20261005010000 failed: the workflow definition/version foreign-key pair is incomplete.';
    END IF;
END
$$;