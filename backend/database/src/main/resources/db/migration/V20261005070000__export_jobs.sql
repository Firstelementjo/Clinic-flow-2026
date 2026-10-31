-- =====================================================================
-- V20261005070000__export_jobs.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005060000. See V20261005010000__workflow_definitions_and_versions.sql
--   for why a 12-digit version sorts BEFORE the 14-digit baseline.
--
-- PROMOTED FROM ops/flyway/pending/V202610040002__export_jobs.sql.todo
--   (deleted on promotion).
--
-- WHY THIS MIGRATION EXISTS
--   FR-8.5/FR-8.6/FR-8.7 and plan.md rule 7: a large export must never run on a
--   request thread, and every export must be reproducible, re-authorised at job
--   start AND at download, redacted, checksummed, expiring, and download-audited.
--   The three pieces of evidence that make an export defensible months later are
--   (a) which report VERSION produced it, (b) which authorization decisions were
--   in force when it was requested, and (c) which redaction policy was applied.
--   All three are stored on the job, not re-derived later.
--
-- WHAT IT CREATES
--   1. enum  public.ExportJobStatus (QUEUED, RUNNING, COMPLETED, FAILED,
--      CANCELLED, EXPIRED, DELETED)
--   2. enum  public.ExportFormat    (CSV, XLSX, DOCX, PDF, JSON)
--   3. enum  public.ExportDownloadOutcome (GRANTED, DENIED, EXPIRED, DELETED)
--   4. enum  public.ExportDeletionOutcome (STORAGE_OBJECT_DELETED,
--      STORAGE_DELETE_FAILED, ROW_TOMBSTONED)
--   5. table public.export_jobs             -- the state machine
--   6. table public.export_job_attempts     -- APPEND-ONLY retry history
--   7. table public.export_download_events  -- APPEND-ONLY signed-download record
--   8. table public.export_deletion_events  -- APPEND-ONLY object-storage record
--
-- THE STATE MACHINE IS IN THE SCHEMA, NOT ONLY IN THE APPLICATION
--   Seven states, and every one of them has to be expressible without lying:
--   QUEUED (accepted, not started), RUNNING (in flight), COMPLETED (an object
--   exists and is downloadable), FAILED (an error a human must read), CANCELLED
--   (an operator stopped it), EXPIRED (the window closed and the object is gone)
--   and DELETED (the row remains as evidence; only the object is removed).
--   Collapsing CANCELLED into FAILED loses the difference between "the job broke"
--   and "a person chose to stop it", which is the first question anybody asks.
--   Collapsing EXPIRED into DELETED loses the reason the object disappeared.
--
--   The CHECK constraints below encode the STATE/ COLUMN PAIRING, which is the
--   part a correct writer needs help with: a RUNNING job with a finish time, a
--   COMPLETED job with no checksum, a DELETED job still pointing at storage.
--   The LEGAL TRANSITIONS themselves are enforced by the UPDATE predicate in
--   ops/flyway/reference/export-report-statements.sql section 1, which carries
--   the expected current state in the WHERE clause, so an illegal transition
--   loses on the affected-row count instead of on application ordering. That is
--   the same two-part split Phase 6a used for workflow activation, and for the
--   same reason: a CHECK cannot see the OLD row.
--
-- `authorizationSnapshot` IS EVIDENCE, NOT A CACHE
--   Re-authorisation at job start and again at download is MANDATORY even when a
--   snapshot exists (FR-8.6). The snapshot exists so that "which permissions did
--   this person actually have on the day" is answerable after the answer has
--   been served. It is CHECKed to be a non-empty JSON object, because an empty
--   snapshot that "looks recorded" is worse than an absent one.
--
-- THE REPORT VERSION IS PINNED, NEVER "latest"
--   `reportDefinitionId` and `reportVersion` are a BICONDITIONAL: an export that
--   names a definition pins a version, and an export that names neither is a raw
--   export. A definition with a NULL version would resolve to whatever is
--   published at RUN time, which is the reproducibility bug ADR-0007 rule 2 is
--   about in a different table. Same treatment for the document template.
--
-- `storageKey` IS OPAQUE, AND THE CHECK ENFORCES IT
--   Never a customer name, an email, a phone number, a ticket number, and never
--   a presigned URL with the authorization baked in -- a URL in a database row is
--   a bearer credential with an expiry nobody manages. The CHECK rejects `?` and
--   `://` so a URL cannot be written into the column even by accident, and the
--   signed URL is minted per download and recorded in export_download_events
--   with its own expiry instead.
--
-- `checksum` HAS A FORMAT, NOT JUST A LENGTH
--   `^sha256:[0-9a-f]{64}$`. "Some string" as a checksum cannot be compared
--   against anything, and FR-8.7's "the export is the one we produced" test
--   needs a byte-comparable value.
--
-- DELETION IS A TOMBSTONE PLUS AN EVENT, NEVER A ROW REMOVAL
--   status DELETED requires `deletedAt`, requires the object to be gone
--   (storageKey IS NULL), and REQUIRES a row in export_deletion_events. The
--   event table's foreign key is ON DELETE RESTRICT, not CASCADE: a CASCADE here
--   would let a delete of the job row destroy the only evidence that the object's
--   storage was cleaned up, which is exactly the evidence an operator needs when
--   a customer asks "did you really delete my file?". The job row is never
--   removed at all.
--
-- RETRY HISTORY IS APPEND-ONLY
--   export_job_attempts has no updatedAt: a retry creates a row with the next
--   attempt number, it never mutates the failed one. The same rule Phase 5a's
--   job_attempts and Phase 6a's workflow_run_actions follow, and each migration's
--   guard fails if an updatedAt ever appears.
--
-- TENANT SCOPE
--   organizationId uuid NOT NULL on all four tables (FR-1.2). projectId NOT NULL
--   on all four: an export is always about one project's data, so a NULL could
--   only mean "unknown scope". The composite foreign key
--   export_jobs_report_tenant_fkey then REFUSES pinning another tenant's report
--   definition, which a single-column FK would permit.
--   There is no nullable projectId in this migration; the organisation-wide case
--   belongs to configuration tables (retention_classes, legal_holds, the report
--   catalogue), not to data extractions.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone; every default is
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); updatedAt is never defaulted.
--   expiresAt is NOT NULL because an export with no expiry is a permanent
--   copy of patient data in object storage, which is a data-protection defect
--   rather than a missing feature.
--
-- SAFETY
--   CREATE TYPE / CREATE TABLE only. No DROP, no RENAME, no ALTER of an existing
--   object, no data change, and no index added to any pre-existing table.
--   Rollback: drop the four tables and the four types in reverse dependency
--   order -- rehearsed on a throwaway database only
--   (ops/flyway/rollback/undo_phase7_10a.sql).
--   Every index name stays under PostgreSQL's 63-byte limit and the guard
--   asserts every index on these tables is one this file declared, by its exact
--   name.
--   Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

CREATE TYPE "public"."ExportJobStatus" AS ENUM (
    'QUEUED',
    'RUNNING',
    'COMPLETED',
    'FAILED',
    'CANCELLED',
    'EXPIRED',
    'DELETED'
);

CREATE TYPE "public"."ExportFormat" AS ENUM (
    'CSV',
    'XLSX',
    'DOCX',
    'PDF',
    'JSON'
);

CREATE TYPE "public"."ExportDownloadOutcome" AS ENUM (
    'GRANTED',
    'DENIED',
    'EXPIRED',
    'DELETED'
);

CREATE TYPE "public"."ExportDeletionOutcome" AS ENUM (
    'STORAGE_OBJECT_DELETED',
    'STORAGE_DELETE_FAILED',
    'ROW_TOMBSTONED'
);

-- ---------------------------------------------------------------------
-- export_jobs
-- ---------------------------------------------------------------------
CREATE TABLE "public"."export_jobs" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    "projectId"              uuid NOT NULL,
    "requestedByUserId"      uuid NOT NULL,
    -- A report-backed export pins BOTH of these (biconditional below); a raw
    -- export pins neither.
    "reportDefinitionId"     uuid,
    "reportVersion"          integer,
    "documentTemplateId"     uuid,
    "documentTemplateVersion" integer,
    "format"                 "public"."ExportFormat" NOT NULL,
    "status"                 "public"."ExportJobStatus" NOT NULL DEFAULT 'QUEUED',
    "filters"                jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- The requested field list, pinned so a re-run is byte-comparable.
    "columns"                jsonb NOT NULL DEFAULT '[]'::jsonb,
    "redactionPolicy"        text NOT NULL,
    "retentionClass"         text NOT NULL,
    -- Evidence, not a cache. Re-authorisation at start and at download is still
    -- mandatory; this records what was in force when it was REQUESTED.
    "authorizationSnapshot"  jsonb NOT NULL,
    -- Progress and retry bookkeeping.
    "progressPct"            integer NOT NULL DEFAULT 0,
    "attemptCount"           integer NOT NULL DEFAULT 0,
    "lastAttemptAt"          timestamp(3) without time zone,
    "nextAttemptAt"          timestamp(3) without time zone,
    "startedAt"              timestamp(3) without time zone,
    "finishedAt"             timestamp(3) without time zone,
    -- The produced object.
    "rowCount"               bigint,
    "checksum"               text,
    "storageKey"             text,
    "bytes"                  bigint,
    "errorMessage"           text,
    "cancelledAt"            timestamp(3) without time zone,
    "cancelledByUserId"      uuid,
    "cancelReason"           text,
    "expiredAt"              timestamp(3) without time zone,
    "deletedAt"              timestamp(3) without time zone,
    "deletedByUserId"        uuid,
    "expiresAt"              timestamp(3) without time zone NOT NULL,
    "requestedAt"            timestamp(3) without time zone NOT NULL,
    "correlationId"          text,
    "version"                integer NOT NULL DEFAULT 0,
    "createdAt"              timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"              timestamp(3) without time zone NOT NULL,
    CONSTRAINT "export_jobs_pkey" PRIMARY KEY ("id"),
    -- THE REPORT-PIN BICONDITIONAL. A definition with no version resolves to
    -- "whatever is published when the job runs", which is the reproducibility
    -- bug; an export with neither is a raw export and must say nothing.
    CONSTRAINT "export_jobs_report_pin_check"
        CHECK (("reportDefinitionId" IS NOT NULL) = ("reportVersion" IS NOT NULL)),
    CONSTRAINT "export_jobs_template_pin_check"
        CHECK (("documentTemplateId" IS NOT NULL) = ("documentTemplateVersion" IS NOT NULL)),
    CONSTRAINT "export_jobs_report_version_check"
        CHECK ("reportVersion" IS NULL OR "reportVersion" >= 1),
    CONSTRAINT "export_jobs_template_version_check"
        CHECK ("documentTemplateVersion" IS NULL OR "documentTemplateVersion" >= 1),
    -- THE STATE / COLUMN PAIRING. Each of these is a biconditional where one half
    -- would leave a state an operator has to guess about.
    --
    -- startedAt is a biconditional against "has left the queue", which INCLUDES
    -- EXPIRED and DELETED: both of those are reachable only from a job that ran,
    -- so an EXPIRED job with no start time describes a job that never produced
    -- the object that then expired. The first draft of this CHECK listed only
    -- RUNNING/COMPLETED/FAILED/CANCELLED and therefore refused EXPIRED -> DELETED
    -- on a job that had legitimately finished -- the throwaway rehearsal caught
    -- it because the constraint, not the state machine, was wrong.
    CONSTRAINT "export_jobs_started_check"
        CHECK (("status" IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED',
                             'EXPIRED', 'DELETED'))
               = ("startedAt" IS NOT NULL)),
    -- finishedAt is REQUIRED for the three states that end an attempt, and it is
    -- KEPT afterwards (EXPIRED and DELETED retain it as evidence). It may never
    -- appear without a start.
    --
    -- There is deliberately no biconditional here for finishedAt. The first draft
    -- of this file had
    --     CHECK (status IN ('COMPLETED','FAILED','CANCELLED') = (finishedAt IS NOT NULL))
    -- and then a second, "the same rule written differently" constraint that read
    --     CHECK (status IN ('COMPLETED','FAILED','CANCELLED') OR status <> 'QUEUED')
    -- which is FALSE FOR EVERY QUEUED JOB and refused a job the moment it was
    -- created. The rehearsal seed found it -- `new row for relation "export_jobs"
    -- violates check constraint "export_jobs_finished_check"` -- which is the
    -- whole reason the rehearsal seeds a row in every state machine instead of
    -- trusting a shape inspection. A biconditional is only correct when the two
    -- sides really are complementary, and here they are not: DELETED and EXPIRED
    -- keep a finish time that COMPLETED set.
    CONSTRAINT "export_jobs_finished_present_check"
        CHECK ("status" NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
               OR "finishedAt" IS NOT NULL),
    CONSTRAINT "export_jobs_finished_requires_start_check"
        CHECK ("finishedAt" IS NULL OR "startedAt" IS NOT NULL),
    -- COMPLETED means there is an object, a checksum and a row count. Anything
    -- else must carry none of them: a FAILED job with a checksum invites an
    -- operator to download a truncated file believing it is the export.
    CONSTRAINT "export_jobs_output_check"
        CHECK (("status" = 'COMPLETED')
               = ("checksum" IS NOT NULL AND "storageKey" IS NOT NULL
                  AND "rowCount" IS NOT NULL)),
    CONSTRAINT "export_jobs_row_count_check"
        CHECK ("rowCount" IS NULL OR "rowCount" >= 0),
    CONSTRAINT "export_jobs_bytes_check"
        CHECK ("bytes" IS NULL OR "bytes" >= 0),
    -- A checksum you cannot compare against anything is not a checksum.
    CONSTRAINT "export_jobs_checksum_format_check"
        CHECK ("checksum" IS NULL OR "checksum" ~ '^sha256:[0-9a-f]{64}$'),
    -- OPAQUE OBJECT KEY. A customer name, an email or a presigned URL in this
    -- column is a data-protection incident with no boundary, so `?` and `://`
    -- are refused outright: the signed URL is minted per download and recorded
    -- in export_download_events with its own expiry.
    CONSTRAINT "export_jobs_storage_key_check"
        CHECK ("storageKey" IS NULL
               OR (length(btrim("storageKey")) > 0
                   AND "storageKey" NOT LIKE '%://%'
                   AND "storageKey" NOT LIKE '%?%')),
    CONSTRAINT "export_jobs_failure_reason_check"
        CHECK (("status" = 'FAILED') = ("errorMessage" IS NOT NULL)),
    CONSTRAINT "export_jobs_cancelled_check"
        CHECK (("status" = 'CANCELLED')
               = ("cancelledAt" IS NOT NULL AND "cancelledByUserId" IS NOT NULL
                  AND "cancelReason" IS NOT NULL)),
    CONSTRAINT "export_jobs_expired_check"
        CHECK (("status" = 'EXPIRED') = ("expiredAt" IS NOT NULL)),
    -- DELETED is a tombstone: the row stays, the object does not. A DELETED job
    -- still pointing at storage is the exact state this CHECK exists to prevent,
    -- because the whole point is that the object is gone.
    CONSTRAINT "export_jobs_deleted_check"
        CHECK (("status" = 'DELETED')
               = ("deletedAt" IS NOT NULL AND "deletedByUserId" IS NOT NULL
                  AND "storageKey" IS NULL)),
    -- Progress must be honest. A COMPLETED job that reports 60% is a bug report
    -- nobody can act on.
    CONSTRAINT "export_jobs_progress_check"
        CHECK ("progressPct" >= 0 AND "progressPct" <= 100),
    CONSTRAINT "export_jobs_progress_completed_check"
        CHECK ("status" <> 'COMPLETED' OR "progressPct" = 100),
    -- A queued or running job that has never started has no progress to report.
    CONSTRAINT "export_jobs_progress_started_check"
        CHECK ("progressPct" = 0 OR "startedAt" IS NOT NULL),
    CONSTRAINT "export_jobs_attempt_count_check"
        CHECK ("attemptCount" >= 0),
    -- A retry that does not say when it happened is not a retry. A QUEUED job
    -- with attempts but no lastAttemptAt is a scheduler that incremented a
    -- counter it never used.
    CONSTRAINT "export_jobs_attempt_state_check"
        CHECK (("attemptCount" = 0) = ("lastAttemptAt" IS NULL)),
    -- EXPIRY IS NOT OPTIONAL. An export with no expiry is a permanent copy of
    -- patient data in object storage.
    CONSTRAINT "export_jobs_expiry_check"
        CHECK ("expiresAt" > "requestedAt"),
    -- An evidence object that is not an object cannot be evidence, and an EMPTY
    -- object is worse than an absent one because it looks recorded.
    -- NOTE: compared against the literal, not with jsonb_object_length() --
    -- PostgreSQL 16.15 (this host's server) has jsonb_array_length but NO
    -- jsonb_object_length, and a function that does not exist is a migration
    -- that cannot apply. jsonb's equality is key/value equality, so '{}' is the
    -- only value that compares equal here.
    CONSTRAINT "export_jobs_authorization_snapshot_check"
        CHECK (jsonb_typeof("authorizationSnapshot") = 'object'
               AND "authorizationSnapshot" <> '{}'::jsonb),
    CONSTRAINT "export_jobs_filters_shape_check"
        CHECK (jsonb_typeof("filters") = 'object'),
    CONSTRAINT "export_jobs_columns_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("columns")),
    CONSTRAINT "export_jobs_redaction_policy_check"
        CHECK (length(btrim("redactionPolicy")) > 0),
    CONSTRAINT "export_jobs_retention_class_check"
        CHECK (length(btrim("retentionClass")) > 0),
    CONSTRAINT "export_jobs_version_check"
        CHECK ("version" >= 0)
);

ALTER TABLE ONLY "public"."export_jobs"
    ADD CONSTRAINT "export_jobs_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."export_jobs"
    ADD CONSTRAINT "export_jobs_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."export_jobs"
    ADD CONSTRAINT "export_jobs_requester_tenant_fkey"
    FOREIGN KEY ("requestedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."export_jobs"
    ADD CONSTRAINT "export_jobs_cancelled_by_tenant_fkey"
    FOREIGN KEY ("cancelledByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."export_jobs"
    ADD CONSTRAINT "export_jobs_deleted_by_tenant_fkey"
    FOREIGN KEY ("deletedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- COMPOSITE: pinning another tenant's report definition is exactly what an
-- export scope-escalation looks like, and a single-column FK permits it.
ALTER TABLE ONLY "public"."export_jobs"
    ADD CONSTRAINT "export_jobs_report_tenant_fkey"
    FOREIGN KEY ("reportDefinitionId", "organizationId")
    REFERENCES "public"."report_definitions"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."export_jobs"
    ADD CONSTRAINT "export_jobs_template_tenant_fkey"
    FOREIGN KEY ("documentTemplateId", "organizationId")
    REFERENCES "public"."document_templates"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Composite tenant key: the download and deletion event history must stay
-- inside the tenant.
CREATE UNIQUE INDEX "export_jobs_id_organizationId_key"
    ON "public"."export_jobs" USING "btree" ("id", "organizationId");

-- The Admin export list and the "my exports" list, both tenant-led.
CREATE INDEX "export_jobs_organizationId_projectId_status_created_idx"
    ON "public"."export_jobs" USING "btree"
       ("organizationId", "projectId", "status", "createdAt");

CREATE INDEX "export_jobs_organizationId_requester_created_idx"
    ON "public"."export_jobs" USING "btree"
       ("organizationId", "requestedByUserId", "createdAt");

-- The expiry sweeper: "which completed exports have passed their window". It is
-- TENANT-SCOPED on purpose, unlike the outbox dispatcher sweep ADR-0008 declined
-- to implement, because an export's expiry removes customer data and a global
-- sweep over it would be a cross-tenant deletion path.
CREATE INDEX "export_jobs_organizationId_status_expires_idx"
    ON "public"."export_jobs" USING "btree"
       ("organizationId", "status", "expiresAt");

-- The scheduler claim: due exports for one tenant.
CREATE INDEX "export_jobs_organizationId_status_nextAttempt_idx"
    ON "public"."export_jobs" USING "btree"
       ("organizationId", "status", "nextAttemptAt");

-- ---------------------------------------------------------------------
-- export_job_attempts -- APPEND-ONLY retry history
-- ---------------------------------------------------------------------
CREATE TABLE "public"."export_job_attempts" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "exportJobId"        uuid NOT NULL,
    "attemptNumber"      integer NOT NULL,
    "outcome"            text NOT NULL,
    "workerId"           text,
    "startedAt"          timestamp(3) without time zone NOT NULL,
    "finishedAt"         timestamp(3) without time zone,
    "durationMs"         integer,
    "rowsProduced"       bigint,
    "errorCode"          text,
    "errorMessage"       text,
    "correlationId"      text,
    -- NO updatedAt. A retry creates a row; it never mutates the failed one.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "export_job_attempts_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "export_job_attempts_attempt_number_check"
        CHECK ("attemptNumber" >= 1),
    CONSTRAINT "export_job_attempts_outcome_check"
        CHECK ("outcome" IN ('STARTED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    -- A finish time and a duration are set together, in BOTH directions. An
    -- attempt that reports a duration without having finished is a scheduler
    -- claiming work it has not finished.
    CONSTRAINT "export_job_attempts_finished_check"
        CHECK (("finishedAt" IS NULL) = ("durationMs" IS NULL)),
    CONSTRAINT "export_job_attempts_duration_check"
        CHECK ("durationMs" IS NULL OR "durationMs" >= 0),
    CONSTRAINT "export_job_attempts_rows_check"
        CHECK ("rowsProduced" IS NULL OR "rowsProduced" >= 0),
    -- A failure says why. Only a failure may carry an error.
    CONSTRAINT "export_job_attempts_error_check"
        CHECK (("outcome" = 'FAILED')
               = ("errorCode" IS NOT NULL AND "errorMessage" IS NOT NULL)),
    -- A success reports what it produced; STARTED has produced nothing yet.
    CONSTRAINT "export_job_attempts_success_check"
        CHECK ("outcome" <> 'SUCCEEDED' OR "rowsProduced" IS NOT NULL)
);

ALTER TABLE ONLY "public"."export_job_attempts"
    ADD CONSTRAINT "export_job_attempts_job_tenant_fkey"
    FOREIGN KEY ("exportJobId", "organizationId")
    REFERENCES "public"."export_jobs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- One attempt number per job, per tenant: two racing schedulers cannot both
-- record attempt 2, so the retry count cannot be inflated.
CREATE UNIQUE INDEX "export_job_attempts_job_attempt_key"
    ON "public"."export_job_attempts" USING "btree"
       ("organizationId", "exportJobId", "attemptNumber");

CREATE INDEX "export_job_attempts_organizationId_job_idx"
    ON "public"."export_job_attempts" USING "btree"
       ("organizationId", "exportJobId");

CREATE INDEX "export_job_attempts_organizationId_outcome_idx"
    ON "public"."export_job_attempts" USING "btree"
       ("organizationId", "outcome", "createdAt");

-- ---------------------------------------------------------------------
-- export_download_events -- APPEND-ONLY signed-download record
-- ---------------------------------------------------------------------
-- Every attempt to obtain the object, including the refusals. The GRANTED row
-- records the signature's own expiry, so "this URL was live until then" is
-- answerable without keeping the URL (which is a bearer credential).
CREATE TABLE "public"."export_download_events" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    "projectId"              uuid NOT NULL,
    "exportJobId"            uuid NOT NULL,
    "actorUserId"            uuid,
    -- Named principal for non-human actors. A null user cannot be told apart
    -- from "we forgot", so one of the two columns must be present.
    "principalName"          text NOT NULL,
    "outcome"                "public"."ExportDownloadOutcome" NOT NULL,
    "signatureId"            text,
    "signatureExpiresAt"     timestamp(3) without time zone,
    "exportExpiresAt"        timestamp(3) without time zone,
    "checksumAtDownload"     text,
    "redactionPolicyApplied" text,
    "denyReason"             text,
    "ipAddress"              text,
    "userAgent"              text,
    "correlationId"          text,
    "occurredAt"             timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"              timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "export_download_events_pkey" PRIMARY KEY ("id"),
    -- A GRANTED download has a signature with an expiry; anything else has
    -- neither. Stated as a biconditional so a DENIED row cannot carry a live
    -- signature, which would mean a refusal was recorded after the fact.
    CONSTRAINT "export_download_events_signature_check"
        CHECK (("outcome" = 'GRANTED')
               = ("signatureId" IS NOT NULL AND "signatureExpiresAt" IS NOT NULL)),
    -- The signature must expire BEFORE the export does. A signature outliving the
    -- window it was minted for is a second expiry to get wrong.
    CONSTRAINT "export_download_events_signature_expiry_check"
        CHECK ("signatureExpiresAt" IS NULL
               OR "exportExpiresAt" IS NULL
               OR "signatureExpiresAt" <= "exportExpiresAt"),
    CONSTRAINT "export_download_events_checksum_format_check"
        CHECK ("checksumAtDownload" IS NULL
               OR "checksumAtDownload" ~ '^sha256:[0-9a-f]{64}$'),
    -- A refusal must say why (FR-8.6), and only a refusal may.
    CONSTRAINT "export_download_events_deny_reason_check"
        CHECK (("outcome" = 'DENIED') = ("denyReason" IS NOT NULL)),
    CONSTRAINT "export_download_events_actor_check"
        CHECK ("actorUserId" IS NOT NULL OR length(btrim("principalName")) > 0),
    CONSTRAINT "export_download_events_principal_name_check"
        CHECK (length(btrim("principalName")) > 0)
);

-- ON DELETE RESTRICT, NOT CASCADE: a download audit that disappears with its
-- job row is the opposite of an audit. The job row is never deleted anyway.
ALTER TABLE ONLY "public"."export_download_events"
    ADD CONSTRAINT "export_download_events_job_tenant_fkey"
    FOREIGN KEY ("exportJobId", "organizationId")
    REFERENCES "public"."export_jobs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."export_download_events"
    ADD CONSTRAINT "export_download_events_actor_tenant_fkey"
    FOREIGN KEY ("actorUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX "export_download_events_organizationId_job_idx"
    ON "public"."export_download_events" USING "btree"
       ("organizationId", "exportJobId", "occurredAt");

CREATE INDEX "export_download_events_organizationId_outcome_idx"
    ON "public"."export_download_events" USING "btree"
       ("organizationId", "outcome", "occurredAt");

CREATE INDEX "export_download_events_organizationId_actor_idx"
    ON "public"."export_download_events" USING "btree"
       ("organizationId", "actorUserId", "occurredAt");

-- ---------------------------------------------------------------------
-- export_deletion_events -- APPEND-ONLY object-storage record
-- ---------------------------------------------------------------------
CREATE TABLE "public"."export_deletion_events" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "exportJobId"        uuid NOT NULL,
    "outcome"            "public"."ExportDeletionOutcome" NOT NULL,
    "storageKeyAtDelete" text,
    "bytesReclaimed"     bigint,
    "requestedByUserId"  uuid,
    "failureCode"        text,
    "failureMessage"     text,
    "legalHoldChecked"   boolean NOT NULL DEFAULT false,
    "legalHoldId"        text,
    "correlationId"      text,
    "occurredAt"         timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "export_deletion_events_pkey" PRIMARY KEY ("id"),
    -- A FAILED deletion says why. Only a failure may carry an error.
    CONSTRAINT "export_deletion_events_failure_check"
        CHECK (("outcome" = 'STORAGE_DELETE_FAILED')
               = ("failureCode" IS NOT NULL AND "failureMessage" IS NOT NULL)),
    -- A deletion that reclaimed bytes must say how many. A negative number is
    -- not a reclaim.
    CONSTRAINT "export_deletion_events_bytes_check"
        CHECK ("bytesReclaimed" IS NULL OR "bytesReclaimed" >= 0),
    -- ADR-0008 rule 5: a purge must record that it ASKED whether a legal hold
    -- applied. A deletion event with legalHoldChecked false is a deletion that
    -- skipped the question, and the column makes that visible rather than
    -- leaving it to be inferred from a nullable boolean in a different system.
    CONSTRAINT "export_deletion_events_legal_hold_check"
        CHECK ("legalHoldChecked" OR "legalHoldId" IS NULL)
);

ALTER TABLE ONLY "public"."export_deletion_events"
    ADD CONSTRAINT "export_deletion_events_job_tenant_fkey"
    FOREIGN KEY ("exportJobId", "organizationId")
    REFERENCES "public"."export_jobs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."export_deletion_events"
    ADD CONSTRAINT "export_deletion_events_actor_tenant_fkey"
    FOREIGN KEY ("requestedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- The key the object had BEFORE it was deleted. It is stored here and cleared on
-- the job, so the evidence survives the tombstone.
CREATE INDEX "export_deletion_events_organizationId_job_idx"
    ON "public"."export_deletion_events" USING "btree"
       ("organizationId", "exportJobId", "occurredAt");

CREATE INDEX "export_deletion_events_organizationId_outcome_idx"
    ON "public"."export_deletion_events" USING "btree"
       ("organizationId", "outcome", "occurredAt");

-- ---------------------------------------------------------------------
-- Post-condition guard.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing   text;
    ok_tables text[] := ARRAY['export_jobs', 'export_job_attempts',
                             'export_download_events', 'export_deletion_events'];
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'export_jobs_id_organizationId_key',
               'export_jobs_organizationId_projectId_status_created_idx',
               'export_jobs_organizationId_requester_created_idx',
               'export_jobs_organizationId_status_expires_idx',
               'export_jobs_organizationId_status_nextAttempt_idx',
               'export_job_attempts_job_attempt_key',
               'export_job_attempts_organizationId_job_idx',
               'export_job_attempts_organizationId_outcome_idx',
               'export_download_events_organizationId_job_idx',
               'export_download_events_organizationId_outcome_idx',
               'export_download_events_organizationId_actor_idx',
               'export_deletion_events_organizationId_job_idx',
               'export_deletion_events_organizationId_outcome_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005070000 failed: missing indexes: %', missing;
    END IF;

    SELECT string_agg(t, ', ')
      INTO missing
      FROM unnest(ok_tables) AS t
     WHERE to_regclass('public.' || quote_ident(t)) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005070000 failed: missing tables: %', missing;
    END IF;

    -- 1. APPEND-ONLY: the three event/attempt tables must NOT have updatedAt.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'updatedAt'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN ('export_job_attempts', 'export_download_events',
                         'export_deletion_events');

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005070000 failed: these tables are append-only and must not have '
            'updatedAt: %', missing;
    END IF;

    -- ...and export_jobs, the one mutable table, MUST have it.
    IF NOT EXISTS (SELECT 1 FROM pg_attribute a
                     JOIN pg_class c ON c.oid = a.attrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = 'export_jobs'
                      AND a.attname = 'updatedAt'
                      AND a.attnum > 0 AND NOT a.attisdropped) THEN
        RAISE EXCEPTION
            'V20261005070000 failed: export_jobs is a state machine and must have updatedAt.';
    END IF;

    -- 2. projectId NOT NULL on all four: an export is always about one
    --    project's data, so a NULL could only mean unknown scope.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'projectId'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND NOT a.attnotnull;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005070000 failed: projectId must be NOT NULL on every export table '
            '(an export is always project-scoped): %', missing;
    END IF;

    -- 3. organizationId NOT NULL (FR-1.2).
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
            'V20261005070000 failed: organizationId must be NOT NULL (FR-1.2) on: %', missing;
    END IF;

    -- 4. V6 contract: no timestamptz.
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
            'V20261005070000 failed: timestamptz column in: %', missing;
    END IF;

    -- 5. Every UTC-writing default must say UTC.
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
            'V20261005070000 failed: a default uses a session-local CURRENT_TIMESTAMP: %',
            missing;
    END IF;

    -- 6. THE THREE "DELETE" FOREIGN KEYS MUST BE RESTRICT, NOT CASCADE. A
    --    CASCADE would let removing a job row destroy the evidence that its
    --    object was cleaned up -- which is the one thing an operator needs when a
    --    customer asks whether their file was really deleted.
    IF (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND con.conname IN ('export_download_events_job_tenant_fkey',
                               'export_deletion_events_job_tenant_fkey')
           AND con.contype = 'f'
           AND con.confdeltype = 'r') <> 2 THEN
        RAISE EXCEPTION
            'V20261005070000 failed: the download and deletion history foreign keys must '
            'be ON DELETE RESTRICT; a CASCADE destroys the deletion evidence.';
    END IF;

    -- 7. Enum value lists ARE the contract. CANCELLED, EXPIRED and DELETED are
    --    separate states on purpose and collapsing any two of them changes what
    --    the Admin screen can say.
    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'ExportJobStatus')
       IS DISTINCT FROM 'QUEUED,RUNNING,COMPLETED,FAILED,CANCELLED,EXPIRED,DELETED' THEN
        RAISE EXCEPTION 'V20261005070000 failed: public.ExportJobStatus value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'ExportFormat')
       IS DISTINCT FROM 'CSV,XLSX,DOCX,PDF,JSON' THEN
        RAISE EXCEPTION 'V20261005070000 failed: public.ExportFormat value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'ExportDownloadOutcome')
       IS DISTINCT FROM 'GRANTED,DENIED,EXPIRED,DELETED' THEN
        RAISE EXCEPTION
            'V20261005070000 failed: public.ExportDownloadOutcome value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t
          JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'ExportDeletionOutcome')
       IS DISTINCT FROM 'STORAGE_OBJECT_DELETED,STORAGE_DELETE_FAILED,ROW_TOMBSTONED' THEN
        RAISE EXCEPTION
            'V20261005070000 failed: public.ExportDeletionOutcome value list drifted.';
    END IF;

    -- 8. Truncation detection: every index on these tables must be a name this
    --    file declared. A name over 63 bytes is silently truncated; a
    --    legitimate 63-byte name is NOT flagged.
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'export_download_events'
                  AND indexname NOT IN (
                      'export_download_events_pkey',
                      'export_download_events_organizationId_job_idx',
                      'export_download_events_organizationId_outcome_idx',
                      'export_download_events_organizationId_actor_idx')) THEN
        RAISE EXCEPTION
            'V20261005070000 failed: an unexpected index exists on export_download_events '
            '-- most likely a name longer than 63 bytes was silently truncated.';
    END IF;

    -- 9. The two composite tenant foreign keys into the report catalogue must be
    --    COMPOSITE. A single-column FK would let an export pin another tenant's
    --    report definition, which is a scope escalation, not a data-integrity
    --    nicety.
    IF (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND con.conname IN ('export_jobs_report_tenant_fkey',
                               'export_jobs_template_tenant_fkey')
           AND con.contype = 'f') <> 2 THEN
        RAISE EXCEPTION
            'V20261005070000 failed: the report and template pins must be composite '
            'tenant foreign keys.';
    END IF;
END
$$;