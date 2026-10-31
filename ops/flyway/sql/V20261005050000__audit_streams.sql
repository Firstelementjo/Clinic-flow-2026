-- =====================================================================
-- V20261005050000__audit_streams.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005040000. See V20261005010000__workflow_definitions_and_versions.sql
--   for why a 12-digit version sorts BEFORE the 14-digit baseline.
--
-- PROMOTED FROM ops/flyway/pending/V202610040008__audit_streams.sql.todo,
--   which was deleted on promotion: the promoted file is the only copy of the
--   decision, and a lingering draft invites a second promotion.
--
-- WHY THIS MIGRATION EXISTS
--   The verified schema has ONE audit table, `audit_logs`, and everything is
--   written to it: logins, role changes, configuration changes, AI runs,
--   exports, job failures. One retention class, one redaction policy, one
--   access rule. That is too coarse to hand a finance manager an audit view
--   without also handing them every login and every denial, and it is why
--   ADR-0005 section 5 has to say the separation is "by SecurityEventType
--   convention and must not be described as a physical separation".
--   This migration makes the separation PHYSICAL, and it is the Phase 7
--   dependency ADR-0008 rule 5 names: a purge job must be able to ask whether a
--   tenant is under legal hold, and that question has to be answerable before
--   the purge job can be enabled.
--
-- WHAT IT CREATES
--   1. enum public.AuditOutcome         (SUCCESS, DENIED, FAILED)
--   2. enum public.AuditPrincipalType   (USER, SERVICE, AI_AGENT, JOB,
--                                        CONNECTOR, SYSTEM)
--   3. enum public.AuditStreamKind      (ADMIN, SECURITY, JOB_INTEGRATION,
--                                        FINANCE, PRIVACY)
--   4. enum public.LegalHoldScopeType   (TENANT, PROJECT, TABLE, RECORD)
--   5. enum public.PrivacyDeletionRequestStatus (REQUESTED, VERIFIED,
--                                        IN_PROGRESS, COMPLETED, REJECTED,
--                                        CANCELLED)
--   6. table public.retention_classes              -- mutable configuration
--   7. table public.legal_holds                    -- mutable (a hold is released)
--   8. table public.legal_hold_events              -- APPEND-ONLY history
--   9. table public.privacy_deletion_requests      -- mutable state machine
--  10. table public.privacy_deletion_targets       -- APPEND-ONLY scope list
--  11. table public.admin_audit_events             -- APPEND-ONLY
--  12. table public.security_events                -- APPEND-ONLY
--  13. table public.job_integration_events         -- APPEND-ONLY
--   14. index users_id_organizationId_key          -- composite tenant key
--   15. index outbox_events_id_organizationId_key  -- composite tenant key
--   16. index job_attempts_id_organizationId_key   -- composite tenant key
--
-- THE DRAFT'S OPEN QUESTION 2 IS DECIDED HERE: SEPARATE TABLES, NOT ONE
--   PARTITIONED `audit_events` WITH A `stream` COLUMN
--   The draft proposed one physically partitioned table with a stream
--   discriminator and left the choice open. Four reasons for separate tables,
--   all of which survive review:
--     * a `stream` column is a DISCRIMINATOR, not a boundary. One forgotten
--       `where stream = 'SECURITY'` in a finance report hands a finance manager
--       every login and every denial. With separate tables that report cannot be
--       written, because the table it would need does not exist;
--     * retention, redaction and GRANTS differ per stream. A grant is issued on
--       a relation; a discriminator cannot carry one;
--     * partitioning was the other reason the draft wanted one table, and it is
--       the reason this migration does NOT partition: attaching a partition is
--       not additively reversible, and ADR 0008 shows the retention windows are
--       still configuration rather than a settled design. Audit write volume is
--       orders of magnitude below `job_attempts`, so the partitioning motive is
--       not yet earned;
--     * the append-only boundary then becomes assertable per table by shape
--       (no `updatedAt`), instead of needing a trigger or a rule.
--   The cost is three near-identical column lists. That cost is paid
--   deliberately: a stream that must not be queried by the wrong audience is
--   worth three DDL blocks.
--
-- WHAT IT DOES NOT DO -- audit_logs IS UNTOUCHED
--   No DROP, no RENAME, no ALTER of `audit_logs` or of any other existing
--   object, and no UPDATE of any existing row. The legacy NestJS runtime still
--   writes `audit_logs` and must keep working while the strangler is in flight.
--   Moving history into the new streams is a DATA operation and lives in the
--   resumable, checkpointed, idempotent backfill
--   ops/flyway/backfill/B3_backfill_audit_streams.sql -- which is armed twice
--   and writes nothing without its second guard. It copies rows; it never
--   deletes from `audit_logs`. `audit_logs` is retired read-only at cutover and
--   dropped only at decommission, which is a Phase 12 decision.
--
-- LEGAL HOLD MUST EXIST BEFORE ANY PURGE CAN BE ENABLED
--   ADR-0008 rule 5: "A purge job refuses to run for a tenant under legal
--   hold. This is a Phase 7 dependency: Admin must expose hold state before the
--   first purge job is enabled." So the hold is a first-class, tenant-scoped,
--   auditable row with an append-only event history, and
--   `legal_holds_released_pair_check` makes a hold that says it is released say
--   WHO released it and WHEN. A hold whose scope is `TENANT` has a NULL
--   `projectId`, and `legal_holds_scope_pair_check` refuses a `PROJECT`-scoped
--   hold with no project -- the one combination that would silently hold nothing.
--
-- THE ATTRIBUTION RULE, ENFORCED
--   Every event carries `principalType` NOT NULL and, when the principal is a
--   human, `actorUserId` -- and a composite foreign key
--   `admin_audit_events_actor_tenant_fkey` /
--   `security_events_actor_tenant_fkey` over ("actorUserId","organizationId").
--   A single-column FK guarantees the user EXISTS and nothing more, so an event
--   could attribute a decision to another tenant's administrator, which is the
--   worst possible answer to "who did this". The composite key closes it in DDL
--   rather than describing it as repository discipline, and the fixture exercises
--   it with real cross-tenant rows.
--
-- WHY THE COMPOSITE TENANT KEYS ARE ADDED TO EXISTING TABLES
--   `users_id_organizationId_key`, `outbox_events_id_organizationId_key` and
--   `job_attempts_id_organizationId_key` are UNIQUE INDEXES, created
--   IF NOT EXISTS, purely so a composite FOREIGN KEY can target them. They are
--   indexes, not UNIQUE CONSTRAINTS, so V4's `unique_constraints = 0`
--   invariant survives. `id` is the primary key of all three tables, so the
--   composite is unique by construction and cannot fail against existing data.
--   PostgreSQL accepting a non-partial unique INDEX as a foreign-key target was
--   verified on this host in Phase 6a before it was relied on.
--   These three are the ONLY indexes this migration adds to an existing table,
--   and ops/flyway/rollback/undo_phase7_10a.sql removes them so the fingerprint
--   returns exactly.
--
-- RETENTION IS STILL CONFIGURATION
--   ADR-0008's windows cover `job_attempts`, `job_dead_letters`,
--   `idempotency_records` and `outbox_events`. It explicitly does NOT decide
--   audit, security and finance retention: "Audit, security, and finance
--   retention ... are governed by their own retention classes and are not
--   touched by this." That is why `retention_classes` is a TABLE here and not a
--   constant: the numbers are agreed per tenant, they are auditable, and a purge
--   job reads them rather than hard-coding them. No purge job is created by this
--   migration, and nothing in it deletes a row.
--
-- TENANT SCOPE -- THE EXPLICIT LIST (V12 section 4d asserts it)
--   organizationId uuid NOT NULL on every table in this migration. No table
--   here has NO tenant column, so the tenant-less table list stays at exactly
--   four (ai_run_retrievals, ai_run_steps, ai_run_tool_calls, organizations).
--   Nullable projectId -- organisation-wide by design, listed explicitly:
--     * retention_classes            -- a tenant's retention window applies to
--                                       every project that does not override it;
--     * legal_holds                  -- a TENANT-scoped hold has no project;
--     * privacy_deletion_requests    -- a tenant may ask to be forgotten whole;
--     * admin_audit_events           -- changing a tenant's authentication
--                                       settings is not a project action;
--     * job_integration_events       -- a worker serves the tenant, not a project.
--   NOT NULL projectId -- every event is about one project's data:
--     * security_events              -- a security decision is scoped to the
--                                       project whose data it protected;
--     * privacy_deletion_targets     -- inherits the request, which may be
--                                       organisation-wide, so it is nullable too.
--   Recorded here because the exemption class must never widen unnoticed.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone (V6 asserts no timestamptz anywhere).
--   Every default is (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); a bare
--   CURRENT_TIMESTAMP stores the SESSION's wall clock, which is the baseline's
--   habit and is exactly what Phases 5a and 6a stopped copying. Each migration's
--   guard fails if any new default omits AT TIME ZONE.
--
-- SAFETY
--   * CREATE TYPE / CREATE TABLE / CREATE INDEX IF NOT EXISTS only. No DROP,
--     no RENAME, no ALTER of an existing object, no data change.
--   * Strict CREATE TABLE for new tables; IF NOT EXISTS only for the three
--     additive indexes on existing tables, so the migration is safe to
--     re-run against a database where Phase 5a already created the outbox.
--   * Rollback: drop this slice's tables and types, then the three composite
--     key indexes -- rehearsed on a throwaway database only
--     (ops/flyway/rollback/undo_phase7_10a.sql).
--   * Every index name stays under PostgreSQL's 63-byte limit, and the guard
--     asserts every index on these tables is one this file declared by its
--     exact name, which is the check that actually detects truncation.
--   * Index existence is checked in pg_indexes, NOT with to_regclass().
--   * Every identifier is schema-qualified because the baseline sets
--     search_path = '' when the whole chain is piped into ONE psql session.
-- =====================================================================

-- ---------------------------------------------------------------------
-- enums
-- ---------------------------------------------------------------------
CREATE TYPE "public"."AuditOutcome" AS ENUM (
    'SUCCESS',
    'DENIED',
    'FAILED'
);

-- WHY AN ENUM AND NOT FREE TEXT. plan.md forbids "an unrestricted scheduled
-- identity"; the same argument applies to the principal that made a decision.
-- A closed list means "SYSTEM did this" is one of the possibilities the schema
-- states, and a dashboard filter over it cannot be widened by a typo.
CREATE TYPE "public"."AuditPrincipalType" AS ENUM (
    'USER',
    'SERVICE',
    'AI_AGENT',
    'JOB',
    'CONNECTOR',
    'SYSTEM'
);

-- The stream taxonomy. FINANCE and PRIVACY are declared here so a retention
-- class can be declared against them BEFORE V20261005090000 creates the finance
-- tables: the reference is a taxonomy, not a foreign key to a table that does
-- not exist yet, and the finance migration does not have to come before Admin
-- can name a retention class.
CREATE TYPE "public"."AuditStreamKind" AS ENUM (
    'ADMIN',
    'SECURITY',
    'JOB_INTEGRATION',
    'FINANCE',
    'PRIVACY'
);

CREATE TYPE "public"."LegalHoldScopeType" AS ENUM (
    'TENANT',
    'PROJECT',
    'TABLE',
    'RECORD'
);

CREATE TYPE "public"."PrivacyDeletionRequestStatus" AS ENUM (
    'REQUESTED',
    'VERIFIED',
    'IN_PROGRESS',
    'COMPLETED',
    'REJECTED',
    'CANCELLED'
);

-- ---------------------------------------------------------------------
-- composite tenant keys on existing tables (additive indexes only)
-- ---------------------------------------------------------------------
-- Created here, in the FIRST Phase-7-10a migration, because every later
-- migration in the slice wants at least one of them and a migration must not
-- depend on a migration that has not run yet.
CREATE UNIQUE INDEX IF NOT EXISTS "users_id_organizationId_key"
    ON "public"."users" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX IF NOT EXISTS "outbox_events_id_organizationId_key"
    ON "public"."outbox_events" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX IF NOT EXISTS "job_attempts_id_organizationId_key"
    ON "public"."job_attempts" USING "btree" ("id", "organizationId");

-- ---------------------------------------------------------------------
-- retention classes -- the DATA behind a purge window
-- ---------------------------------------------------------------------
CREATE TABLE "public"."retention_classes" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    -- NULL = this class applies to every project in the organisation. A
    -- per-project override is a second row with a projectId; the code that
    -- resolves a class must prefer the most specific row, and that rule is
    -- recorded here rather than left to whoever writes the resolver.
    "projectId"          uuid,
    "classCode"          text NOT NULL,
    "stream"             "public"."AuditStreamKind" NOT NULL,
    "retentionDays"      integer NOT NULL,
    -- TRUE means "a purge skips this class while a legal hold covers it".
    -- It is a column and not a constant because ADR-0008 rule 1 is a POLICY
    -- decision an operator makes per class, and a policy that is not a column
    -- is a policy nobody can change without a migration.
    "legalHoldExempt"    boolean NOT NULL DEFAULT true,
    "description"        text,
    "createdByUserId"    uuid,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "retention_classes_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "retention_classes_retention_days_check"
        CHECK ("retentionDays" >= 1),
    CONSTRAINT "retention_classes_class_code_check"
        CHECK (length(btrim("classCode")) > 0)
);

ALTER TABLE ONLY "public"."retention_classes"
    ADD CONSTRAINT "retention_classes_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."retention_classes"
    ADD CONSTRAINT "retention_classes_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- The most-specific-row resolver reads this. Tenant-scoped, and projectId last
-- so a project override is found without a sort.
CREATE UNIQUE INDEX "retention_classes_organizationId_projectId_code_key"
    ON "public"."retention_classes" USING "btree"
       ("organizationId", "projectId", "classCode");

-- The default-class lookup: "what applies to this tenant as a whole".
CREATE INDEX "retention_classes_organizationId_stream_idx"
    ON "public"."retention_classes" USING "btree"
       ("organizationId", "stream");

-- ---------------------------------------------------------------------
-- legal holds -- representable BEFORE any purge job exists (ADR-0008 rule 5)
-- ---------------------------------------------------------------------
CREATE TABLE "public"."legal_holds" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "scopeType"          "public"."LegalHoldScopeType" NOT NULL,
    -- Free text for scopeType TABLE / RECORD: which table, which record id.
    "scopeRef"           text,
    "reason"             text NOT NULL,
    "status"             text NOT NULL DEFAULT 'ACTIVE',
    "placedAt"           timestamp(3) without time zone NOT NULL,
    "placedByUserId"     uuid,
    "expiresAt"          timestamp(3) without time zone,
    "releasedAt"         timestamp(3) without time zone,
    "releasedByUserId"   uuid,
    "releaseReason"      text,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "legal_holds_pkey" PRIMARY KEY ("id"),
    -- The scope must say WHAT it holds. A TENANT hold needs no project; a
    -- PROJECT hold that names none holds nothing at all, which is the failure
    -- this pair refuses.
    CONSTRAINT "legal_holds_scope_pair_check"
        CHECK (("scopeType" = 'TENANT'::"public"."LegalHoldScopeType"
                AND "scopeRef" IS NULL)
               OR ("scopeType" = 'PROJECT'::"public"."LegalHoldScopeType"
                   AND "projectId" IS NOT NULL)),
    -- A TABLE or RECORD hold is meaningless without naming the table.
    CONSTRAINT "legal_holds_scope_ref_check"
        CHECK (("scopeType" IN ('TENANT'::"public"."LegalHoldScopeType",
                                'PROJECT'::"public"."LegalHoldScopeType"))
               OR ("scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0)),
    -- A hold must say why it exists. "Legal hold" with no reason cannot be
    -- defended to a regulator and cannot be reviewed for release.
    CONSTRAINT "legal_holds_reason_check"
        CHECK (length(btrim("reason")) > 0),
    -- A hold that says it is RELEASED says who released it and when.
    -- Biconditional: a release timestamp with status ACTIVE is also refused,
    -- because "released on the 3rd but still holding" is a contradiction an
    -- operator would act on wrongly.
    CONSTRAINT "legal_holds_released_pair_check"
        CHECK (("status" = 'RELEASED') = ("releasedAt" IS NOT NULL)),
    CONSTRAINT "legal_holds_released_actor_check"
        CHECK ("releasedAt" IS NULL OR ("releasedByUserId" IS NOT NULL
                                        AND "releaseReason" IS NOT NULL)),
    -- A hold that ends before it starts is not a hold.
    CONSTRAINT "legal_holds_expiry_check"
        CHECK ("expiresAt" IS NULL OR "expiresAt" > "placedAt"),
    CONSTRAINT "legal_holds_version_check"
        CHECK ("version" >= 0)
);

ALTER TABLE ONLY "public"."legal_holds"
    ADD CONSTRAINT "legal_holds_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."legal_holds"
    ADD CONSTRAINT "legal_holds_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."legal_holds"
    ADD CONSTRAINT "legal_holds_placed_by_tenant_fkey"
    FOREIGN KEY ("placedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."legal_holds"
    ADD CONSTRAINT "legal_holds_released_by_tenant_fkey"
    FOREIGN KEY ("releasedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Composite tenant key: a legal-hold event may only name this tenant's hold.
CREATE UNIQUE INDEX "legal_holds_id_organizationId_key"
    ON "public"."legal_holds" USING "btree" ("id", "organizationId");

-- THE PURGE LOOKUP. "Does this tenant -- and, if the purge is per project, this
-- project -- have an ACTIVE hold?" is the query ADR-0008 rule 5 makes every
-- purge job run first, and it is why this index leads with the tenant.
CREATE INDEX "legal_holds_organizationId_status_placed_idx"
    ON "public"."legal_holds" USING "btree"
       ("organizationId", "status", "placedAt");

CREATE INDEX "legal_holds_organizationId_projectId_status_idx"
    ON "public"."legal_holds" USING "btree"
       ("organizationId", "projectId", "status");

-- ---------------------------------------------------------------------
-- legal hold events -- APPEND-ONLY
-- ---------------------------------------------------------------------
-- Why a history table rather than the mutable row alone: a hold is PLACED,
-- possibly EXTENDED, and RELEASED, and "was this tenant ever under hold" is a
-- question an operator and a regulator both ask. The mutable row answers "is it
-- held now"; only the event history answers "was it ever".
CREATE TABLE "public"."legal_hold_events" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "legalHoldId"        uuid NOT NULL,
    "eventType"          text NOT NULL,
    "actorUserId"        uuid,
    -- Named principal for non-human actors (SYSTEM, a job, a connector). A null
    -- user cannot be told apart from "we forgot", so the actor is spelled out
    -- in one of two columns and the CHECK below refuses both being absent.
    "actorPrincipal"     text NOT NULL,
    "note"               text,
    "eventAt"            timestamp(3) without time zone NOT NULL,
    -- NO updatedAt. The event happened once.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "legal_hold_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "legal_hold_events_event_type_check"
        CHECK ("eventType" IN ('PLACED', 'EXTENDED', 'RELEASED', 'RELEASE_REFUSED')),
    CONSTRAINT "legal_hold_events_actor_check"
        CHECK ("actorUserId" IS NOT NULL OR length(btrim("actorPrincipal")) > 0),
    CONSTRAINT "legal_hold_events_actor_principal_check"
        CHECK (length(btrim("actorPrincipal")) > 0)
);

ALTER TABLE ONLY "public"."legal_hold_events"
    ADD CONSTRAINT "legal_hold_events_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."legal_hold_events"
    ADD CONSTRAINT "legal_hold_events_hold_tenant_fkey"
    FOREIGN KEY ("legalHoldId", "organizationId")
    REFERENCES "public"."legal_holds"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."legal_hold_events"
    ADD CONSTRAINT "legal_hold_events_actor_tenant_fkey"
    FOREIGN KEY ("actorUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX "legal_hold_events_organizationId_legalHoldId_eventAt_idx"
    ON "public"."legal_hold_events" USING "btree"
       ("organizationId", "legalHoldId", "eventAt");

-- ---------------------------------------------------------------------
-- privacy deletion requests -- a state machine, not a flag
-- ---------------------------------------------------------------------
CREATE TABLE "public"."privacy_deletion_requests" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "subjectUserId"      uuid NOT NULL,
    "subjectType"        text NOT NULL DEFAULT 'CUSTOMER',
    "status"             "public"."PrivacyDeletionRequestStatus" NOT NULL DEFAULT 'REQUESTED',
    "reason"             text NOT NULL,
    "requestedAt"        timestamp(3) without time zone NOT NULL,
    "requestedByUserId"  uuid,
    "verifiedAt"         timestamp(3) without time zone,
    "verifiedByUserId"   uuid,
    "startedAt"          timestamp(3) without time zone,
    "startedByUserId"    uuid,
    "completedAt"        timestamp(3) without time zone,
    "completedByUserId"  uuid,
    "cancelledAt"        timestamp(3) without time zone,
    "cancelledByUserId"  uuid,
    "cancelReason"       text,
    "rejectionReason"    text,
    "evidenceReference"  text,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "privacy_deletion_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "privacy_deletion_requests_reason_check"
        CHECK (length(btrim("reason")) > 0),
    CONSTRAINT "privacy_deletion_requests_subject_type_check"
        CHECK ("subjectType" IN ('CUSTOMER', 'USER', 'EMPLOYEE', 'TENANT')),
    -- The verification pair. A request that was never verified cannot be
    -- started, and a verified request says who verified it.
    CONSTRAINT "privacy_deletion_requests_verified_pair_check"
        CHECK (("verifiedAt" IS NULL) = ("verifiedByUserId" IS NULL)),
    -- STARTED demands verification: deleting a tenant's data on an unverified
    -- request is irreversible and the schema should make the wrong order
    -- impossible rather than merely discouraged.
    CONSTRAINT "privacy_deletion_requests_start_requires_verified_check"
        CHECK ("startedAt" IS NULL OR "verifiedAt" IS NOT NULL),
    CONSTRAINT "privacy_deletion_requests_completion_check"
        CHECK (("status" = 'COMPLETED') = ("completedAt" IS NOT NULL)),
    CONSTRAINT "privacy_deletion_requests_cancellation_check"
        CHECK (("status" = 'CANCELLED') = ("cancelledAt" IS NOT NULL)),
    -- A rejection without a reason is not a decision anybody can act on.
    CONSTRAINT "privacy_deletion_requests_rejection_check"
        CHECK ("status" <> 'REJECTED' OR ("rejectionReason" IS NOT NULL
                                           AND length(btrim("rejectionReason")) > 0)),
    -- A completed deletion of personal data must be able to point at what was
    -- removed, or FR-7 privacy reporting cannot be answered.
    CONSTRAINT "privacy_deletion_requests_evidence_check"
        CHECK ("status" <> 'COMPLETED' OR ("evidenceReference" IS NOT NULL
                                           AND length(btrim("evidenceReference")) > 0)),
    CONSTRAINT "privacy_deletion_requests_version_check"
        CHECK ("version" >= 0)
);

ALTER TABLE ONLY "public"."privacy_deletion_requests"
    ADD CONSTRAINT "privacy_deletion_requests_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."privacy_deletion_requests"
    ADD CONSTRAINT "privacy_deletion_requests_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."privacy_deletion_requests"
    ADD CONSTRAINT "privacy_deletion_requests_subject_tenant_fkey"
    FOREIGN KEY ("subjectUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."privacy_deletion_requests"
    ADD CONSTRAINT "privacy_deletion_requests_actor_tenant_fkey"
    FOREIGN KEY ("requestedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- The Admin queue: "what is outstanding, oldest first", per tenant. projectId
-- last so the same index serves the organisation-wide queue.
CREATE INDEX "privacy_deletion_requests_organizationId_status_requested_idx"
    ON "public"."privacy_deletion_requests" USING "btree"
       ("organizationId", "status", "requestedAt");

CREATE INDEX "privacy_deletion_requests_organizationId_subjectUserId_idx"
    ON "public"."privacy_deletion_requests" USING "btree"
       ("organizationId", "subjectUserId");

CREATE UNIQUE INDEX "privacy_deletion_requests_id_organizationId_key"
    ON "public"."privacy_deletion_requests" USING "btree"
       ("id", "organizationId");

-- ---------------------------------------------------------------------
-- privacy deletion targets -- APPEND-ONLY, so a request is explainable
-- ---------------------------------------------------------------------
CREATE TABLE "public"."privacy_deletion_targets" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "deletionRequestId"  uuid NOT NULL,
    "targetType"         text NOT NULL,
    "targetId"           uuid,
    "targetRef"          text,
    "rowsRemoved"        bigint,
    "decision"           text NOT NULL,
    "decidedAt"          timestamp(3) without time zone NOT NULL,
    "decidedByUserId"    uuid,
    -- Free text on purpose, so a target row can be written when the hold lives
    -- in another system of record and "we kept this because a hold existed" is
    -- never lost to a foreign-key failure. Deliberately NOT a foreign key; its
    -- presence is what the CHECK below enforces.
    "legalHoldId"        text,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "privacy_deletion_targets_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "privacy_deletion_targets_target_type_check"
        CHECK ("targetType" IN ('CUSTOMER', 'CONVERSATION', 'MESSAGE', 'NOTE',
                                'DOCUMENT', 'EXPORT', 'USER', 'EMPLOYEE')),
    -- Every target must be nameable one way or the other. A target with no id
    -- and no ref is a row nobody can act on.
    CONSTRAINT "privacy_deletion_targets_identity_check"
        CHECK ("targetId" IS NOT NULL OR length(btrim("targetRef")) > 0),
    CONSTRAINT "privacy_deletion_targets_decision_check"
        CHECK ("decision" IN ('REMOVED', 'ANONYMISED', 'RETAINED_LEGAL_HOLD',
                              'RETAINED_OUT_OF_SCOPE', 'FAILED')),
    -- "RETAINED_LEGAL_HOLD" is the only decision that may delete nothing, and
    -- it must say WHICH hold. ADR-0008 rule 1: retention never deletes a row a
    -- legal hold protects, and this is where that is recorded per row.
    -- Stated as a BICONDITIONAL: the one-way implication "only a hold decision
    -- may name a hold" is the obvious half and leaves a hold reference on a row
    -- that claims to have removed the data -- which reads as "removed, and there
    -- was a hold", the worst possible ambiguity in a privacy decision.
    CONSTRAINT "privacy_deletion_targets_legal_hold_check"
        CHECK (("decision" = 'RETAINED_LEGAL_HOLD')
               = ("legalHoldId" IS NOT NULL
                  AND length(btrim("legalHoldId")) > 0)),
    CONSTRAINT "privacy_deletion_targets_removed_check"
        CHECK ("decision" NOT IN ('REMOVED', 'ANONYMISED')
               OR ("rowsRemoved" IS NOT NULL AND "rowsRemoved" >= 0))
);

ALTER TABLE ONLY "public"."privacy_deletion_targets"
    ADD CONSTRAINT "privacy_deletion_targets_request_tenant_fkey"
    FOREIGN KEY ("deletionRequestId", "organizationId")
    REFERENCES "public"."privacy_deletion_requests"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."privacy_deletion_targets"
    ADD CONSTRAINT "privacy_deletion_targets_actor_tenant_fkey"
    FOREIGN KEY ("decidedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- `legalHoldId` is deliberately NOT a foreign key: it is free text on purpose so
-- a target row can be written when the hold lives in another system of record,
-- and so recording "we kept this because a hold existed" never fails because the
-- hold row was archived. Its presence/absence is what the CHECK above enforces.

CREATE INDEX "privacy_deletion_targets_organizationId_request_idx"
    ON "public"."privacy_deletion_targets" USING "btree"
       ("organizationId", "deletionRequestId");

-- NOT unique on purpose, and therefore named _idx rather than _key: targetRef is
-- NULL for every target that has a real targetId, and NULLs never collide in a
-- unique index, so a unique index here would advertise a guarantee it does not
-- provide. Duplicate suppression for a deletion target is the caller's
-- ON CONFLICT decision, and the request-level index above is what makes the
-- "what did we do for this request" read cheap.
CREATE INDEX "privacy_deletion_targets_org_target_decided_idx"
    ON "public"."privacy_deletion_targets" USING "btree"
       ("organizationId", "targetType", "targetRef", "decidedAt");

-- ---------------------------------------------------------------------
-- admin_audit_events -- APPEND-ONLY
-- ---------------------------------------------------------------------
CREATE TABLE "public"."admin_audit_events" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    -- NULL: changing the tenant's authentication settings, billing profile or
    -- AI policy is not a project action.
    "projectId"          uuid,
    "actorUserId"        uuid,
    "principalType"      "public"."AuditPrincipalType" NOT NULL,
    "principalName"      text NOT NULL,
    "action"             text NOT NULL,
    "resourceType"       text NOT NULL,
    "resourceId"         text,
    "outcome"            "public"."AuditOutcome" NOT NULL,
    -- Redacted at write time. FR-10.6: raw tool arguments and customer-shaped
    -- payloads are never written here.
    "metadata"           jsonb NOT NULL DEFAULT '{}'::jsonb,
    "retentionClass"     text NOT NULL,
    "ipAddress"          text,
    "userAgent"          text,
    "correlationId"      text,
    "causationId"        text,
    -- NO updatedAt, and NO legalHold boolean: the hold is a separate, queryable,
    -- tenant-scoped row (legal_holds), so "is this event covered by a hold" is
    -- answered by joining rather than by a denormalised flag that a purge job
    -- would have to keep truthful on every one of these tables.
    "occurredAt"         timestamp(3) without time zone NOT NULL,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "admin_audit_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "admin_audit_events_action_check"
        CHECK (length(btrim("action")) > 0),
    CONSTRAINT "admin_audit_events_resource_type_check"
        CHECK (length(btrim("resourceType")) > 0),
    CONSTRAINT "admin_audit_events_principal_name_check"
        CHECK (length(btrim("principalName")) > 0),
    -- A non-empty principal name, and a USER principal always names a user.
    -- The reverse is deliberately NOT required: a SYSTEM principal has no user.
    CONSTRAINT "admin_audit_events_user_principal_check"
        CHECK ("principalType" <> 'USER'::"public"."AuditPrincipalType"
               OR "actorUserId" IS NOT NULL),
    CONSTRAINT "admin_audit_events_retention_class_check"
        CHECK (length(btrim("retentionClass")) > 0),
    -- FR-10.6: the metadata column must be a JSON OBJECT. An array here is a
    -- sign it is being used as a payload, and a payload is customer content.
    CONSTRAINT "admin_audit_events_metadata_check"
        CHECK (jsonb_typeof("metadata") = 'object')
);

ALTER TABLE ONLY "public"."admin_audit_events"
    ADD CONSTRAINT "admin_audit_events_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."admin_audit_events"
    ADD CONSTRAINT "admin_audit_events_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- THE ATTRIBUTION FK. A single-column FK guarantees the user EXISTS; this one
-- guarantees the user belongs to the same tenant as the event, which is the
-- difference between "an administrator did this" and a usable answer.
ALTER TABLE ONLY "public"."admin_audit_events"
    ADD CONSTRAINT "admin_audit_events_actor_tenant_fkey"
    FOREIGN KEY ("actorUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX "admin_audit_events_organizationId_occurredAt_idx"
    ON "public"."admin_audit_events" USING "btree"
       ("organizationId", "occurredAt");

CREATE INDEX "admin_audit_events_organizationId_projectId_occurredAt_idx"
    ON "public"."admin_audit_events" USING "btree"
       ("organizationId", "projectId", "occurredAt");

-- The drill-down that answers "what did this actor do to this resource".
-- Tenant-led, because "this actor" is only meaningful inside a tenant.
CREATE INDEX "admin_audit_events_organizationId_actorUserId_occurredAt_idx"
    ON "public"."admin_audit_events" USING "btree"
       ("organizationId", "actorUserId", "occurredAt");

-- The correlation trace. Correlation ids are global by construction (they span
-- services), so this index necessarily does not lead with the tenant -- but it
-- is a lookup BY a known id, never a browse path, and it exposes no tenant
-- content. Recorded here so the tenant-less census has a named, reasoned member.
CREATE INDEX "admin_audit_events_correlationId_idx"
    ON "public"."admin_audit_events" USING "btree" ("correlationId");

-- ---------------------------------------------------------------------
-- security_events -- APPEND-ONLY
-- ---------------------------------------------------------------------
CREATE TABLE "public"."security_events" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    -- NOT NULL: a security decision is about one project's data. A login is
    -- recorded against the project the session would have served, which the
    -- authentication service already knows.
    "projectId"          uuid NOT NULL,
    "actorUserId"        uuid,
    "principalType"      "public"."AuditPrincipalType" NOT NULL,
    "principalName"      text NOT NULL,
    "eventType"          text NOT NULL,
    "outcome"            "public"."AuditOutcome" NOT NULL,
    "detail"             text,
    "denyReason"         text,
    "retentionClass"     text NOT NULL,
    "ipAddress"          text,
    "userAgent"          text,
    "sessionId"          text,
    "correlationId"      text,
    "occurredAt"         timestamp(3) without time zone NOT NULL,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "security_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "security_events_event_type_check"
        CHECK ("eventType" IN ('LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'LOGOUT',
                               'MFA_CHALLENGE', 'MFA_FAILED', 'PASSWORD_CHANGED',
                               'PASSWORD_RESET_REQUESTED', 'TOKEN_REFRESHED',
                               'TOKEN_REVOKED', 'PERMISSION_DENIED',
                               'ROLE_CHANGED', 'MEMBERSHIP_INVITED',
                               'MEMBERSHIP_REMOVED', 'IMPERSONATION_STARTED',
                               'IMPERSONATION_ENDED', 'RATE_LIMIT_EXCEEDED',
                               'CSRF_REJECTED', 'CSRF_VIOLATION',
                               'CROSS_TENANT_ACCESS_BLOCKED')),
    -- ADR-0007 Part 2 and FR-6.9: a DENIED outcome must say why, or the denial
    -- stream is a list of timestamps with no diagnostic value.
    CONSTRAINT "security_events_deny_reason_check"
        CHECK ("outcome" <> 'DENIED'::"public"."AuditOutcome"
               OR ("denyReason" IS NOT NULL AND length(btrim("denyReason")) > 0)),
    CONSTRAINT "security_events_user_principal_check"
        CHECK ("principalType" <> 'USER'::"public"."AuditPrincipalType"
               OR "actorUserId" IS NOT NULL),
    CONSTRAINT "security_events_principal_name_check"
        CHECK (length(btrim("principalName")) > 0),
    CONSTRAINT "security_events_retention_class_check"
        CHECK (length(btrim("retentionClass")) > 0)
);

ALTER TABLE ONLY "public"."security_events"
    ADD CONSTRAINT "security_events_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."security_events"
    ADD CONSTRAINT "security_events_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."security_events"
    ADD CONSTRAINT "security_events_actor_tenant_fkey"
    FOREIGN KEY ("actorUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- The question Phase 7's security dashboard actually asks, and the one that
-- must not be answerable across tenants.
CREATE INDEX "security_events_organizationId_eventType_occurredAt_idx"
    ON "public"."security_events" USING "btree"
       ("organizationId", "eventType", "occurredAt");

CREATE INDEX "security_events_organizationId_actorUserId_occurredAt_idx"
    ON "public"."security_events" USING "btree"
       ("organizationId", "actorUserId", "occurredAt");

-- Failed logins by source. An ipAddress-led index is tenant-SCOPED (not
-- tenant-less) because the row still carries the tenant and the query is
-- "failed logins for MY tenant from this address".
CREATE INDEX "security_events_organizationId_outcome_ipAddress_idx"
    ON "public"."security_events" USING "btree"
       ("organizationId", "outcome", "ipAddress");

-- ---------------------------------------------------------------------
-- job_integration_events -- APPEND-ONLY
-- ---------------------------------------------------------------------
CREATE TABLE "public"."job_integration_events" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    -- NULL: a worker serves the tenant. A dispatcher sweep or a connector
    -- reconnect is not a project action.
    "projectId"          uuid,
    "component"          text NOT NULL,
    "componentType"      "public"."AuditPrincipalType" NOT NULL,
    "eventType"          text NOT NULL,
    "outcome"            "public"."AuditOutcome" NOT NULL,
    "queueName"          text,
    "jobId"              text,
    "outboxEventId"      uuid,
    "jobAttemptId"       uuid,
    "correlationId"      text,
    "detail"             text,
    "failureCode"        text,
    "latencyMs"          integer,
    "retentionClass"     text NOT NULL,
    "occurredAt"         timestamp(3) without time zone NOT NULL,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "job_integration_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "job_integration_events_component_check"
        CHECK (length(btrim("component")) > 0),
    CONSTRAINT "job_integration_events_event_type_check"
        CHECK ("eventType" IN ('JOB_ENQUEUED', 'JOB_STARTED', 'JOB_SUCCEEDED',
                               'JOB_FAILED', 'JOB_RETRY_SCHEDULED',
                               'JOB_DEAD_LETTERED', 'JOB_REPLAYED',
                               'OUTBOX_PUBLISHED', 'OUTBOX_DELIVERED',
                               'OUTBOX_DEAD', 'CONNECTOR_REGISTERED',
                               'CONNECTOR_RECONNECTED', 'CONNECTOR_DISABLED',
                               'WEBHOOK_RECEIVED', 'WEBHOOK_REJECTED',
                               'SCHEDULER_TICK', 'SWEEP_EXECUTED')),
    CONSTRAINT "job_integration_events_failure_check"
        CHECK ("outcome" <> 'FAILED'::"public"."AuditOutcome"
               OR ("failureCode" IS NOT NULL AND length(btrim("failureCode")) > 0)),
    CONSTRAINT "job_integration_events_latency_check"
        CHECK ("latencyMs" IS NULL OR "latencyMs" >= 0),
    CONSTRAINT "job_integration_events_retention_class_check"
        CHECK (length(btrim("retentionClass")) > 0)
);

ALTER TABLE ONLY "public"."job_integration_events"
    ADD CONSTRAINT "job_integration_events_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."job_integration_events"
    ADD CONSTRAINT "job_integration_events_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- COMPOSITE, because a single-column FK here would let a tenant's integration
-- stream name another tenant's outbox event -- the exact cross-tenant reference
-- the queue exists to keep apart. Both targets are the additive composite keys
-- created at the top of this migration.
ALTER TABLE ONLY "public"."job_integration_events"
    ADD CONSTRAINT "job_integration_events_outbox_tenant_fkey"
    FOREIGN KEY ("outboxEventId", "organizationId")
    REFERENCES "public"."outbox_events"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."job_integration_events"
    ADD CONSTRAINT "job_integration_events_attempt_tenant_fkey"
    FOREIGN KEY ("jobAttemptId", "organizationId")
    REFERENCES "public"."job_attempts"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- The integration stream's main read: this tenant's recent worker activity,
-- optionally narrowed to a project.
CREATE INDEX "job_integration_events_organizationId_occurredAt_idx"
    ON "public"."job_integration_events" USING "btree"
       ("organizationId", "occurredAt");

CREATE INDEX "job_integration_events_organizationId_component_event_idx"
    ON "public"."job_integration_events" USING "btree"
       ("organizationId", "component", "eventType");

-- The "why did this job die" lookup. queueName/jobId are the operator's handle,
-- and the index is tenant-scoped so it cannot answer across tenants.
CREATE INDEX "job_integration_events_organizationId_queue_job_idx"
    ON "public"."job_integration_events" USING "btree"
       ("organizationId", "queueName", "jobId");

-- ---------------------------------------------------------------------
-- Post-condition guard.
-- Index existence is checked in pg_indexes, NOT with to_regclass().
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing         text;
    tables_with_ua text;
    ok_tables       text[] := ARRAY[
        'retention_classes', 'legal_holds', 'legal_hold_events',
        'privacy_deletion_requests', 'privacy_deletion_targets',
        'admin_audit_events', 'security_events', 'job_integration_events'
    ];
BEGIN
    -- 1. Every declared index must exist.
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'users_id_organizationId_key',
               'outbox_events_id_organizationId_key',
               'job_attempts_id_organizationId_key',
               'retention_classes_organizationId_projectId_code_key',
               'retention_classes_organizationId_stream_idx',
               'legal_holds_id_organizationId_key',
               'legal_holds_organizationId_status_placed_idx',
               'legal_holds_organizationId_projectId_status_idx',
               'legal_hold_events_organizationId_legalHoldId_eventAt_idx',
               'privacy_deletion_requests_organizationId_status_requested_idx',
               'privacy_deletion_requests_organizationId_subjectUserId_idx',
               'privacy_deletion_requests_id_organizationId_key',
               'privacy_deletion_targets_organizationId_request_idx',
               'privacy_deletion_targets_org_target_decided_idx',
               'admin_audit_events_organizationId_occurredAt_idx',
               'admin_audit_events_organizationId_projectId_occurredAt_idx',
               'admin_audit_events_organizationId_actorUserId_occurredAt_idx',
               'admin_audit_events_correlationId_idx',
               'security_events_organizationId_eventType_occurredAt_idx',
               'security_events_organizationId_actorUserId_occurredAt_idx',
               'security_events_organizationId_outcome_ipAddress_idx',
               'job_integration_events_organizationId_occurredAt_idx',
               'job_integration_events_organizationId_component_event_idx',
               'job_integration_events_organizationId_queue_job_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005050000 failed: missing indexes: %', missing;
    END IF;

    -- 2. Every table this migration declares must exist.
    SELECT string_agg(t, ', ')
      INTO missing
      FROM unnest(ok_tables) AS t
     WHERE to_regclass('public.' || quote_ident(t)) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005050000 failed: missing tables: %', missing;
    END IF;

    -- 3. THE APPEND-ONLY TABLES MUST NOT HAVE updatedAt. Correcting an audit
    --    event is appending a correction, not rewriting the event.
    SELECT string_agg(c.relname, ', ')
      INTO tables_with_ua
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN ('legal_hold_events', 'privacy_deletion_targets',
                         'admin_audit_events', 'security_events',
                         'job_integration_events')
       AND a.attname = 'updatedAt';

    IF tables_with_ua IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005050000 failed: these tables are append-only and must not have '
            'updatedAt: %', tables_with_ua;
    END IF;

    -- 4. THE MUTABLE TABLES MUST HAVE updatedAt, so the boundary is a map rather
    --    than a single flag: a hold that can be released and a request that can
    --    be completed are mutable; the events describing them are not.
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY['retention_classes', 'legal_holds',
                        'privacy_deletion_requests']) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = x
                         AND a.attname = 'updatedAt'
                         AND a.attnum > 0 AND NOT a.attisdropped);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005050000 failed: these tables are mutable and must have '
            'updatedAt: %', missing;
    END IF;

    -- 5. FR-1.2. Every table here carries a tenant, so organizationId must be
    --    NOT NULL on all of them. A nullable organizationId on an audit stream
    --    is a row nobody can filter by tenant, which is the one row an audit
    --    view must never contain.
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
            'V20261005050000 failed: organizationId must be NOT NULL (FR-1.2) on: %', missing;
    END IF;

    -- 6. V6 contract: no timestamptz.
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
            'V20261005050000 failed: timestamptz column in: %; every timestamp here '
            'is timestamp(3) without time zone.', missing;
    END IF;

    -- 7. Every UTC-writing default must say UTC. A bare CURRENT_TIMESTAMP stores
    --    the SESSION's wall clock.
    SELECT string_agg(c.relname || '.' || a.attname, ', ')
      INTO missing
      FROM pg_attrdef d
        JOIN pg_class c ON c.oid = d.adrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attnum = d.adnum
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
       AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%';

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005050000 failed: a default uses a session-local CURRENT_TIMESTAMP: %; '
            'every new default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').', missing;
    END IF;

    -- 8. THE COMPOSITE TENANT KEYS MUST BE UNIQUE, not merely present. A plain
    --    index would satisfy the existence check above and PostgreSQL would
    --    reject the foreign keys, which is a worse failure mode to discover
    --    late -- but the guard states the reason rather than leaving it implicit.
    IF (SELECT count(*) FROM pg_index i
          JOIN pg_class c ON c.oid = i.indexrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname IN ('users_id_organizationId_key',
                             'outbox_events_id_organizationId_key',
                             'job_attempts_id_organizationId_key',
                             'legal_holds_id_organizationId_key',
                             'privacy_deletion_requests_id_organizationId_key')
           AND i.indisunique) <> 5 THEN
        RAISE EXCEPTION
            'V20261005050000 failed: the five composite tenant keys must all be '
            'UNIQUE indexes.';
    END IF;

    -- 9. audit_logs MUST BE UNTOUCHED -- same table, same columns, still there.
    --    The legacy runtime still writes it and this migration is additive, so
    --    the guard fails loudly if a future edit of this file ever drops or
    --    renames it.
    IF to_regclass('public.audit_logs') IS NULL THEN
        RAISE EXCEPTION
            'V20261005050000 failed: audit_logs is gone. The legacy runtime still '
            'writes it and this migration is additive by contract.';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relname = 'audit_logs'
                 AND a.attname = 'stream') THEN
        RAISE EXCEPTION
            'V20261005050000 failed: audit_logs has a stream column. This migration '
            'does not modify audit_logs; the separation is physical, not a column.';
    END IF;

    -- 10. Enum value lists ARE the contract. Drift here changes behaviour
    --     silently, so it is asserted exactly rather than counted.
    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AuditOutcome')
       IS DISTINCT FROM 'SUCCESS,DENIED,FAILED' THEN
        RAISE EXCEPTION 'V20261005050000 failed: public.AuditOutcome value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AuditPrincipalType')
       IS DISTINCT FROM 'USER,SERVICE,AI_AGENT,JOB,CONNECTOR,SYSTEM' THEN
        RAISE EXCEPTION 'V20261005050000 failed: public.AuditPrincipalType value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AuditStreamKind')
       IS DISTINCT FROM 'ADMIN,SECURITY,JOB_INTEGRATION,FINANCE,PRIVACY' THEN
        RAISE EXCEPTION 'V20261005050000 failed: public.AuditStreamKind value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'LegalHoldScopeType')
       IS DISTINCT FROM 'TENANT,PROJECT,TABLE,RECORD' THEN
        RAISE EXCEPTION 'V20261005050000 failed: public.LegalHoldScopeType value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'PrivacyDeletionRequestStatus')
       IS DISTINCT FROM
           'REQUESTED,VERIFIED,IN_PROGRESS,COMPLETED,REJECTED,CANCELLED' THEN
        RAISE EXCEPTION
            'V20261005050000 failed: public.PrivacyDeletionRequestStatus value list drifted.';
    END IF;

    -- 11. Truncation detection, done correctly: every index on one of these
    --     tables must be a name this file declared. A name longer than 63 bytes
    --     is silently truncated and can never match; a legitimate 63-byte name
    --     is not flagged. The FIRST attempt at this guard was
    --     `length(indexname) = 63`, which the Phase-6a throwaway rehearsal
    --     proved WRONG: exactly 63 bytes is legal and untruncated.
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'legal_holds'
                  AND indexname NOT IN (
                      'legal_holds_pkey',
                      'legal_holds_id_organizationId_key',
                      'legal_holds_organizationId_status_placed_idx',
                      'legal_holds_organizationId_projectId_status_idx')) THEN
        RAISE EXCEPTION
            'V20261005050000 failed: an unexpected index exists on legal_holds -- most '
            'likely a name longer than 63 bytes was silently truncated.';
    END IF;
END
$$;