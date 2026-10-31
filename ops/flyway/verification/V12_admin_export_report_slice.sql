-- =====================================================================
-- V12_admin_export_report_slice.sql
-- Phase 7-10a / database verification: the audit, report-definition and export
-- slice introduced by V20261005050000, V20261005060000 and V20261005070000.
--
-- READ-ONLY. No DDL, no DML, no object created. Every section is guarded, so on
-- the pre-cutover database (none of the Phase-7-10a migrations applied) each
-- reports PHASE710A_NOT_PRESENT instead of failing, and the evidence bundle stays
-- valid on both sides of the cutover -- the same property V10 and V11 have.
--
-- Why the slice is asserted in THREE tables rather than one: they are three
-- separate concerns with three different retention classes and three different
-- audiences, and merging the verification would make a failure ambiguous about
-- which concern broke.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Presence. The slice is applied as a whole or not at all.
-- ---------------------------------------------------------------------
SELECT CASE
         WHEN to_regclass('public.retention_classes') IS NOT NULL
          AND to_regclass('public.legal_holds') IS NOT NULL
          AND to_regclass('public.admin_audit_events') IS NOT NULL
          AND to_regclass('public.security_events') IS NOT NULL
          AND to_regclass('public.job_integration_events') IS NOT NULL
          AND to_regclass('public.report_definitions') IS NOT NULL
          AND to_regclass('public.export_jobs') IS NOT NULL
         THEN 'PHASE710A_AUDIT_REPORT_EXPORT_PRESENT'
         WHEN to_regclass('public.retention_classes') IS NULL
          AND to_regclass('public.legal_holds') IS NULL
          AND to_regclass('public.admin_audit_events') IS NULL
          AND to_regclass('public.security_events') IS NULL
          AND to_regclass('public.job_integration_events') IS NULL
          AND to_regclass('public.report_definitions') IS NULL
          AND to_regclass('public.export_jobs') IS NULL
         THEN 'PHASE710A_NOT_PRESENT'
         ELSE 'PHASE710A_PARTIAL -- a Phase-7-10a migration failed halfway; stop'
       END AS phase7_10a_slice_state;

-- ---------------------------------------------------------------------
-- 2. Enum value lists. These ARE the contract; a drift changes behaviour.
-- ---------------------------------------------------------------------
SELECT t.typname AS enum_name,
       string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) AS enum_values
  FROM pg_type t
  JOIN pg_enum e ON e.enumtypid = t.oid
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
   AND t.typname IN ('AuditOutcome', 'AuditPrincipalType', 'AuditStreamKind',
                     'LegalHoldScopeType', 'PrivacyDeletionRequestStatus',
                     'ReportStatus', 'DocumentTemplateStatus',
                     'ExportJobStatus', 'ExportFormat',
                     'ExportDownloadOutcome', 'ExportDeletionOutcome')
 GROUP BY t.typname
 ORDER BY t.typname;

-- ---------------------------------------------------------------------
-- 3. Column shape of the slice's tables.
-- ---------------------------------------------------------------------
SELECT c.relname AS table_name,
       a.attnum  AS ord,
       a.attname AS column_name,
       format_type(a.atttypid, a.atttypmod) AS data_type,
       CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END AS nullable,
       COALESCE(pg_get_expr(d.adbin, d.adrelid), '-') AS default_expr
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
 WHERE n.nspname = 'public'
   AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                     'privacy_deletion_requests', 'privacy_deletion_targets',
                     'admin_audit_events', 'security_events',
                     'job_integration_events',
                     'report_definitions', 'report_versions',
                     'document_templates', 'document_template_versions',
                     'export_jobs', 'export_job_attempts',
                     'export_download_events', 'export_deletion_events')
 ORDER BY c.relname, a.attnum;

-- ---------------------------------------------------------------------
-- 4. Summary assertions, MEASURED on the throwaway that carried the whole
--    chain. The numbers move as later migrations land, so they are PRINTED and
--    the hard assertions live in FlywayBaselineAcceptanceTest and in
--    run-verification.ps1's expected-count flags.
-- ---------------------------------------------------------------------
SELECT (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history') AS tables,
       (SELECT count(*) FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r'
           AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                             'privacy_deletion_requests', 'privacy_deletion_targets',
                             'admin_audit_events', 'security_events',
                             'job_integration_events',
                             'report_definitions', 'report_versions',
                             'document_templates', 'document_template_versions',
                             'export_jobs', 'export_job_attempts',
                             'export_download_events', 'export_deletion_events')) AS new_tables,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'f') AS foreign_keys,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'p') AS primary_keys,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c') AS check_constraints,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c'
           AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                             'privacy_deletion_requests', 'privacy_deletion_targets',
                             'admin_audit_events', 'security_events',
                             'job_integration_events',
                             'report_definitions', 'report_versions',
                             'document_templates', 'document_template_versions',
                             'export_jobs', 'export_job_attempts',
                             'export_download_events', 'export_deletion_events')) AS slice_checks,
       (SELECT count(DISTINCT t.typname) FROM pg_type t
          JOIN pg_namespace n ON n.oid = t.typnamespace
          JOIN pg_enum e ON e.enumtypid = t.oid
         WHERE n.nspname = 'public') AS enums,
       (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public') AS indexes;

-- 4a. FR-1.2: no nullable tenant column where the table HAS one. Must be 0.
SELECT count(*) AS nullable_organization_id
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                     'privacy_deletion_requests', 'privacy_deletion_targets',
                     'admin_audit_events', 'security_events',
                     'job_integration_events',
                     'report_definitions', 'report_versions',
                     'document_templates', 'document_template_versions',
                     'export_jobs', 'export_job_attempts',
                     'export_download_events', 'export_deletion_events')
   AND a.attname = 'organizationId' AND NOT a.attnotnull;

-- 4b. V6 contract: no timestamptz. Must be 0.
SELECT count(*) AS timestamptz_columns
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                     'privacy_deletion_requests', 'privacy_deletion_targets',
                     'admin_audit_events', 'security_events',
                     'job_integration_events',
                     'report_definitions', 'report_versions',
                     'document_templates', 'document_template_versions',
                     'export_jobs', 'export_job_attempts',
                     'export_download_events', 'export_deletion_events')
   AND a.attnum > 0 AND NOT a.attisdropped
   AND a.atttypid = 'timestamptz'::regtype;

-- 4c. Every UTC-writing default must name the zone. Must be 0.
SELECT c.relname AS table_name, a.attname AS column_name,
       pg_get_expr(d.adbin, d.adrelid) AS default_expr
  FROM pg_attrdef d
  JOIN pg_class c ON c.oid = d.adrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.adnum
                       AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                     'privacy_deletion_requests', 'privacy_deletion_targets',
                     'admin_audit_events', 'security_events',
                     'job_integration_events',
                     'report_definitions', 'report_versions',
                     'document_templates', 'document_template_versions',
                     'export_jobs', 'export_job_attempts',
                     'export_download_events', 'export_deletion_events')
   AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
   AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%'
 ORDER BY 1, 2;

-- 4d. THE NULLABLE projectId LIST FOR THIS SLICE, printed so it cannot widen
--     unnoticed. Expected, when the slice is present:
--       admin_audit_events, document_template_versions, document_templates,
--       job_integration_events, legal_hold_events, legal_holds,
--       privacy_deletion_requests, privacy_deletion_targets,
--       report_definitions, report_versions, retention_classes
--     NOT in the list, and that is the point: security_events, export_jobs,
--     export_job_attempts, export_download_events and export_deletion_events are
--     all NOT NULL, because each of them is about one project's data.
SELECT c.relname AS table_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0
                      AND NOT a.attisdropped AND a.attname = 'projectId'
 WHERE n.nspname = 'public'
   AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                     'privacy_deletion_requests', 'privacy_deletion_targets',
                     'admin_audit_events', 'security_events',
                     'job_integration_events',
                     'report_definitions', 'report_versions',
                     'document_templates', 'document_template_versions',
                     'export_jobs', 'export_job_attempts',
                     'export_download_events', 'export_deletion_events')
   AND NOT a.attnotnull
 ORDER BY c.relname;

-- 4e. THE APPEND-ONLY BOUNDARY, as an exact map. Expected:
--     admin_audit_events / job_integration_events / legal_hold_events /
--     privacy_deletion_targets / security_events / export_job_attempts /
--     export_download_events / export_deletion_events / report_versions /
--     document_template_versions = append_only
--     retention_classes / legal_holds / privacy_deletion_requests /
--     report_definitions / document_templates / export_jobs = mutable
SELECT c.relname || '=' || CASE WHEN EXISTS (
           SELECT 1 FROM pg_attribute a
            WHERE a.attrelid = c.oid AND a.attnum > 0
              AND NOT a.attisdropped AND a.attname = 'updatedAt')
         THEN 'mutable' ELSE 'append_only' END AS boundary
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND c.relname IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                     'privacy_deletion_requests', 'privacy_deletion_targets',
                     'admin_audit_events', 'security_events',
                     'job_integration_events',
                     'report_definitions', 'report_versions',
                     'document_templates', 'document_template_versions',
                     'export_jobs', 'export_job_attempts',
                     'export_download_events', 'export_deletion_events')
 ORDER BY 1;

-- 4f. audit_logs MUST STILL EXIST AND MUST NOT HAVE BEEN REWRITTEN. The legacy
--     runtime still writes it; this migration is additive and the separation is
--     PHYSICAL, not a column. A `stream` column here means somebody "helpfully"
--     started the migration this schema deliberately refuses to do.
SELECT c.relname AS table_name,
       EXISTS (SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attname = 'stream'
                  AND a.attnum > 0 AND NOT a.attisdropped) AS has_stream_column
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname = 'audit_logs';

-- 4g. THE COMPOSITE TENANT KEYS ADDED TO EXISTING TABLES. All four must be
--     UNIQUE indexes; a plain index would satisfy a name-only check and
--     PostgreSQL would then refuse the composite foreign keys.
SELECT c.relname AS index_name, i.indisunique,
       (SELECT count(*) FROM pg_index x WHERE x.indexrelid = i.indexrelid) AS n
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('users_id_organizationId_key',
                     'outbox_events_id_organizationId_key',
                     'job_attempts_id_organizationId_key',
                     'report_definitions_id_organizationId_key')
 ORDER BY 1;

-- ---------------------------------------------------------------------
-- 5. Index justification.
-- 5a. THE TENANT-LESS ALLOW LIST. Phase 7-10a adds ONE:
--       admin_audit_events_correlationId_idx -- a correlation trace. Correlation
--       ids span services by construction, so the index cannot lead with the
--       tenant; it is a lookup BY a known id, never a browse path, and it exposes
--       no tenant content. The ipAddress index on security_events is NOT here:
--       it leads with organizationId, because the row still carries the tenant.
--     Any index outside the allow list is a tenant-scope regression.
-- ---------------------------------------------------------------------
SELECT tablename, indexname
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename <> 'flyway_schema_history'
   AND indexdef NOT LIKE '%USING btree ("organizationId"%'
   AND indexdef NOT LIKE '%USING btree ("projectId"%'
   AND indexdef NOT LIKE '%USING hnsw%'
   AND indexdef NOT LIKE '%USING btree ("runId"%'
   AND indexdef NOT LIKE '%USING btree ("conversationId"%'
   AND indexdef NOT LIKE '%USING btree ("employeeId"%'
   AND indexdef NOT LIKE '%USING btree ("userId"%'
   AND indexdef NOT LIKE '%USING btree ("knowledgeBaseId"%'
   AND indexdef NOT LIKE '%USING btree ("agentVersionId"%'
   AND indexdef NOT LIKE '%USING btree ("customerId"%'
   AND indexdef NOT LIKE '%UNIQUE%'
 ORDER BY tablename, indexname;

-- 5b. Full inventory of the slice's indexes, intent and leading column side by
--     side.
SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN ('retention_classes', 'legal_holds', 'legal_hold_events',
                     'privacy_deletion_requests', 'privacy_deletion_targets',
                     'admin_audit_events', 'security_events',
                     'job_integration_events',
                     'report_definitions', 'report_versions',
                     'document_templates', 'document_template_versions',
                     'export_jobs', 'export_job_attempts',
                     'export_download_events', 'export_deletion_events')
 ORDER BY tablename, indexname;

-- 5c/5d/5e/5f. The read models must be index-supported, not sequentially
--     scanned. EXPLAIN WITHOUT ANALYZE only -- no rows are read and nothing is
--     executed against the tables. Guarded, because an EXPLAIN naming a
--     Phase-7-10a table is a parse-time error on a database where the slice has
--     not been applied.
DO $$
DECLARE
    plan       text;
    i          int;
    statements text[] := ARRAY[
        'explain (costs off) select id from public.legal_holds
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and status = ''ACTIVE''
          order by "placedAt"',
        'explain (costs off) select id from public.security_events
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "eventType" = ''LOGIN_FAILED''
          order by "occurredAt" desc limit 50',
        'explain (costs off) select id from public.export_jobs
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "projectId" = ''00000000-0000-0000-0000-000000000000''
            and status = ''QUEUED''::"public"."ExportJobStatus"
          order by "createdAt" desc limit 50',
        'explain (costs off) select id from public.export_jobs
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and status = ''COMPLETED''::"public"."ExportJobStatus"
            and "expiresAt" < (now() at time zone ''UTC'')
          order by "expiresAt" limit 100'
    ];
BEGIN
    IF to_regclass('public.legal_holds') IS NULL THEN
        RAISE NOTICE 'V12 5c-5f: PHASE710A_NOT_PRESENT (no read-model plan to prove)';
        RETURN;
    END IF;
    FOR i IN 1 .. 4 LOOP
        RAISE NOTICE 'V12 plan %:', i;
        FOR plan IN EXECUTE statements[i] LOOP
            RAISE NOTICE '   %', plan;
        END LOOP;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 6. CHECK invariants, asserted as EXCEPTIONS where a dropped constraint must
--    be visible here and not only in the fixture.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    audits   bigint;
    security bigint;
    jobs     bigint;
    crossed  bigint;
    unknown  bigint;
BEGIN
    IF to_regclass('public.legal_holds') IS NULL THEN
        RAISE NOTICE 'V12 section 6: PHASE710A_NOT_PRESENT';
        RETURN;
    END IF;

    SELECT count(*) INTO audits   FROM public.admin_audit_events;
    SELECT count(*) INTO security FROM public.security_events;
    SELECT count(*) INTO jobs     FROM public.export_jobs;
    RAISE NOTICE 'V12 volume: admin_audit_events=% security_events=% export_jobs=%',
                 audits, security, jobs;

    -- Cross-tenant negatives, WITH DATA rather than metadata.
    --   a) an event whose tenant does not exist: the FK makes this impossible,
    --      so a non-zero count means an FK was dropped.
    SELECT count(*) INTO unknown
      FROM public.admin_audit_events e
     WHERE NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.id = e."organizationId");

    --   b) a security event whose project belongs to a DIFFERENT tenant. The
    --      project FK only guarantees the project EXISTS.
    SELECT count(*) INTO crossed
      FROM public.security_events e
      JOIN public.projects p ON p.id = e."projectId"
     WHERE p."organizationId" <> e."organizationId";

    RAISE NOTICE 'V12 tenancy: unknown_organization_rows=% cross_project_rows=%',
                 unknown, crossed;

    -- legal_holds: a hold that says it is released names who and when, and a
    -- PROJECT-scoped hold names its project.
    IF EXISTS (SELECT 1 FROM public.legal_holds
                WHERE (status = 'RELEASED') <> ("releasedAt" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V12: a legal hold''s status and releasedAt disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.legal_holds
                WHERE "scopeType" = 'PROJECT'::"public"."LegalHoldScopeType"
                  AND "projectId" IS NULL) THEN
        RAISE EXCEPTION 'V12: a PROJECT-scoped legal hold names no project, so it holds nothing';
    END IF;

    -- privacy_deletion_requests: no deletion may start unverified.
    IF EXISTS (SELECT 1 FROM public.privacy_deletion_requests
                WHERE "startedAt" IS NOT NULL AND "verifiedAt" IS NULL) THEN
        RAISE EXCEPTION 'V12: a privacy deletion started without verification';
    END IF;

    -- privacy_deletion_targets: RETAINED_LEGAL_HOLD names the hold, both ways.
    IF EXISTS (SELECT 1 FROM public.privacy_deletion_targets
                WHERE ("decision" = 'RETAINED_LEGAL_HOLD')
                      <> ("legalHoldId" IS NOT NULL
                          AND length(btrim("legalHoldId")) > 0)) THEN
        RAISE EXCEPTION
            'V12: a privacy target''s legal-hold decision and its hold reference disagree';
    END IF;

    -- audit_events: a USER principal names a user; a DENIED security event says why.
    IF EXISTS (SELECT 1 FROM public.admin_audit_events
                WHERE "principalType" = 'USER'::"public"."AuditPrincipalType"
                  AND "actorUserId" IS NULL) THEN
        RAISE EXCEPTION 'V12: a USER principal with no actor user';
    END IF;

    IF EXISTS (SELECT 1 FROM public.security_events
                WHERE "outcome" = 'DENIED'::"public"."AuditOutcome"
                  AND ("denyReason" IS NULL OR length(btrim("denyReason")) = 0)) THEN
        RAISE EXCEPTION 'V12: a denied security event does not say why';
    END IF;

    -- report_definitions: Arabic-first is part of the product.
    IF EXISTS (SELECT 1 FROM public.report_definitions
                WHERE length(btrim("nameAr")) = 0
                   OR length(btrim("nameEn")) = 0) THEN
        RAISE EXCEPTION 'V12: a report definition is missing its Arabic or English name';
    END IF;

    -- report_definitions: the timezone must be a zone PostgreSQL knows.
    IF EXISTS (SELECT 1 FROM public.report_definitions
                WHERE public.firstelement_valid_timezone("timezone") IS NOT TRUE) THEN
        RAISE EXCEPTION 'V12: a report definition names a timezone PostgreSQL does not know';
    END IF;

    -- report_versions: a DRAFT has never been published; the allow-lists are
    -- arrays of non-empty strings; a RETIRED version names its successor.
    IF EXISTS (SELECT 1 FROM public.report_versions
                WHERE ("state" = 'DRAFT'::"public"."ReportStatus")
                      <> ("publishedAt" IS NULL)) THEN
        RAISE EXCEPTION 'V12: a report version''s state and publishedAt disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.report_versions
                WHERE public.firstelement_json_list_is_string_array("measures") IS NOT TRUE
                   OR public.firstelement_json_list_is_string_array("dimensions") IS NOT TRUE
                   OR public.firstelement_json_list_is_sort("defaultSort") IS NOT TRUE) THEN
        RAISE EXCEPTION
            'V12: a report version''s allow-list is not a list of non-empty strings, or its '
            'sort is not {"field","direction"} shaped';
    END IF;

    IF EXISTS (SELECT 1 FROM public.report_versions
                WHERE "state" = 'RETIRED'::"public"."ReportStatus"
                  AND ("retiredAt" IS NULL OR "supersededByVersionId" IS NULL)) THEN
        RAISE EXCEPTION 'V12: a retired report version names neither when nor its successor';
    END IF;

    -- export_jobs: the state machine. Each pairing is checked in BOTH directions,
    -- because the one-way form is what a migration with half the constraint would
    -- pass.
    IF EXISTS (SELECT 1 FROM public.export_jobs
                WHERE (("status" IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED',
                                     'EXPIRED', 'DELETED')))
                      <> ("startedAt" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V12: an export job''s status and startedAt disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.export_jobs
                WHERE ("status" = 'COMPLETED')
                      <> ("checksum" IS NOT NULL AND "storageKey" IS NOT NULL
                          AND "rowCount" IS NOT NULL)) THEN
        RAISE EXCEPTION
            'V12: an export job''s COMPLETED state and its produced object disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.export_jobs
                WHERE ("status" = 'DELETED')
                      <> ("deletedAt" IS NOT NULL AND "deletedByUserId" IS NOT NULL
                          AND "storageKey" IS NULL)) THEN
        RAISE EXCEPTION
            'V12: a DELETED export job still points at object storage';
    END IF;

    IF EXISTS (SELECT 1 FROM public.export_jobs
                WHERE ("reportDefinitionId" IS NOT NULL) IS DISTINCT FROM
                      ("reportVersion" IS NOT NULL)) THEN
        RAISE EXCEPTION
            'V12: an export job names a report definition without pinning its version, or '
            'pins a version with no definition';
    END IF;

    IF EXISTS (SELECT 1 FROM public.export_jobs
                WHERE "storageKey" LIKE '%://%' OR "storageKey" LIKE '%?%') THEN
        RAISE EXCEPTION
            'V12: an export job''s storageKey looks like a URL, which is a bearer credential';
    END IF;

    IF EXISTS (SELECT 1 FROM public.export_jobs
                WHERE "expiresAt" <= "requestedAt") THEN
        RAISE EXCEPTION 'V12: an export job expires before it was requested';
    END IF;

    -- export_download_events: a GRANTED download has a signature with an expiry
    -- and it expires no later than the export itself.
    IF EXISTS (SELECT 1 FROM public.export_download_events
                WHERE ("outcome" = 'GRANTED')
                      <> ("signatureId" IS NOT NULL AND "signatureExpiresAt" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V12: a download event''s outcome and signature disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.export_download_events
                WHERE "signatureExpiresAt" IS NOT NULL
                  AND "exportExpiresAt" IS NOT NULL
                  AND "signatureExpiresAt" > "exportExpiresAt") THEN
        RAISE EXCEPTION 'V12: a signed download outlives the export it was minted for';
    END IF;

    -- export_deletion_events: a failure says why; a deletion that reclaimed bytes
    -- says how many.
    IF EXISTS (SELECT 1 FROM public.export_deletion_events
                WHERE ("outcome" = 'STORAGE_DELETE_FAILED')
                      <> ("failureCode" IS NOT NULL AND "failureMessage" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V12: a failed storage deletion does not say why';
    END IF;

    IF EXISTS (SELECT 1 FROM public.export_job_attempts
                WHERE ("finishedAt" IS NULL) <> ("durationMs" IS NULL)) THEN
        RAISE EXCEPTION 'V12: an export attempt has half a finished/duration pair';
    END IF;

    IF jobs > 0 THEN
        RAISE NOTICE 'V12 exports: queued=% completed=% failed=% expired=% deleted=%',
            (SELECT count(*) FROM public.export_jobs WHERE status = 'QUEUED'),
            (SELECT count(*) FROM public.export_jobs WHERE status = 'COMPLETED'),
            (SELECT count(*) FROM public.export_jobs WHERE status = 'FAILED'),
            (SELECT count(*) FROM public.export_jobs WHERE status = 'EXPIRED'),
            (SELECT count(*) FROM public.export_jobs WHERE status = 'DELETED');
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 7. The four validator functions the report slice depends on must exist and
--    must still discriminate. A CHECK that silently degraded to a regex would
--    let a nonexistent zone or a blank permission back in.
--
--    Guarded, and inside a DO block rather than a plain SELECT, because a plain
--    SELECT naming a dropped function is a PARSE-time error -- the bundle must
--    report PHASE710A_NOT_PRESENT after an undo, not fail. That was found by
--    running the bundle against the post-undo throwaway, which is the only place
--    the situation arises.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    found int;
BEGIN
    SELECT count(*) INTO found
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND p.proname IN ('firstelement_valid_timezone', 'firstelement_array_has_blank',
                         'firstelement_json_list_is_string_array',
                         'firstelement_json_list_is_sort');

    IF found = 0 THEN
        RAISE NOTICE 'V12 section 7: PHASE710A_NOT_PRESENT (the validator functions are absent)';
        RETURN;
    END IF;

    IF found <> 4 THEN
        RAISE EXCEPTION
            'V12: only % of the four Phase-7-10a validator functions exist. A dropped validator '
            'degrades its CHECK into something that accepts plausible typos.', found;
    END IF;

    IF public.firstelement_valid_timezone('Asia/Riyadh') IS NOT TRUE
       OR public.firstelement_valid_timezone('Asia/Riyaddah') IS NOT FALSE THEN
        RAISE EXCEPTION 'V12: firstelement_valid_timezone no longer discriminates real from typo';
    END IF;

    IF public.firstelement_array_has_blank(ARRAY['a', ' ']) IS NOT TRUE
       OR public.firstelement_array_has_blank(ARRAY['a', 'b']) IS NOT FALSE THEN
        RAISE EXCEPTION 'V12: firstelement_array_has_blank no longer discriminates blank from real';
    END IF;

    IF public.firstelement_json_list_is_string_array('["a","b"]'::jsonb) IS NOT TRUE
       OR public.firstelement_json_list_is_string_array('["a",""]'::jsonb) IS NOT FALSE THEN
        RAISE EXCEPTION
            'V12: firstelement_json_list_is_string_array no longer discriminates a list from noise';
    END IF;

    IF public.firstelement_json_list_is_sort('[{"field":"x","direction":"desc"}]'::jsonb) IS NOT TRUE
       OR public.firstelement_json_list_is_sort('[{"field":"x","direction":"up"}]'::jsonb) IS NOT FALSE THEN
        RAISE EXCEPTION
            'V12: firstelement_json_list_is_sort no longer discriminates a sort from noise';
    END IF;

    RAISE NOTICE 'V12 section 7: all four validator functions exist and discriminate';
END
$$;

-- ---------------------------------------------------------------------
-- 8. Session timezone. A connection whose TimeZone is not UTC resolves
--    (now() AT TIME ZONE 'UTC') against the wrong wall clock for every timestamp
--    the application binds as LocalDateTime. The application must configure
--    this; the schema cannot.
-- ---------------------------------------------------------------------
SHOW TimeZone;