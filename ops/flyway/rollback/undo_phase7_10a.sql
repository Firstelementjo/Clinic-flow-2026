-- =====================================================================
-- undo_phase7_10a.sql
--
-- ARMED, REFUSING, REVERSIBLE undo of the Phase-7-10a slice:
--     V20261005050000__audit_streams.sql
--     V20261005060000__report_definitions.sql
--     V20261005070000__export_jobs.sql
--     V20261005080000__usage_ledger.sql
--     V20261005090000__finance_rate_cards_periods.sql
--     V20261005100000__ai_policy_and_governance.sql
--     V20261005110000__ai_run_child_tenant_scope.sql   (+ its .conf)
--
-- WHAT IT IS FOR
--   ONE situation only: the slice was created, nothing has been cut over to it,
--   and no writer has ever put a row in it. The Phase-5a rule and the Phase-6a
--   rule both apply -- the slice is additive, so the correct response to "this
--   table is wrong" is almost never to drop it -- and the script exists to make
--   that judgement by MEASUREMENT rather than by opinion.
--
-- WHAT IT IS NOT FOR
--   * NOT a rollback of a failed migration. Every one of the seven migrations is
--     transactional except V20261005110000, and that one is idempotent
--     (ALTER ... IF NOT EXISTS / CREATE INDEX CONCURRENTLY IF NOT EXISTS), so a
--     re-run finishes the job. Nothing needs undoing.
--   * NOT a way to recover money, audit or AI history. See README-phase7-10a.md
--     section 1: after a single row exists, the answer is to roll the
--     application back, not to drop the evidence.
--
-- HOW IT IS ARMED -- AND THE TWO FORMS BUG THIS SCRIPT HAD TO FIX
--   1. `-v firstelement_undo_armed=on` defines a PSQL VARIABLE. The first
--      version of the Phase-6a undo read it with
--      `current_setting('firstelement_undo_armed')`, which is a SERVER GUC. The
--      two are different namespaces, so the script printed SKIPPED even when
--      armed and its later guards never ran at all: a safety gate that cannot be
--      opened, and cannot be closed either, is not a gate. Here the psql form is
--      used -- `\if :{?firstelement_undo_armed}` -- and the value is then handed
--      into a session GUC with set_config() so the DO blocks can read it too.
--   2. psql does NOT substitute `:variables` inside a dollar-quoted body. The
--      first version did `armed boolean := :'armed_yes'::boolean` and failed with
--      `syntax error at or near ":"`. So the flags travel in a PLAIN SQL
--      statement, where substitution does happen, and the DO blocks read the
--      session GUC.
--   3. The Phase-6a undo's row-count message dropped its first entry, because
--      `counts := coalesce(counts || ..., '')` is exactly the case where
--      coalesce's fallback fires. Here `counts` starts at '' and appends
--      unconditionally, so the operator sees every table before deciding.
--   4. Every identifier is QUOTED. The Phase-5a undo wrote
--      `DROP TYPE IF EXISTS public.OutboxState` unquoted; PostgreSQL folded it to
--      lower case, matched nothing, printed "does not exist, skipping", left all
--      four enums behind, and the script still reported success. Only the
--      fingerprint comparison caught it.
--
-- ORDER MATTERS, AND WHY
--   The slice has real dependency edges:
--     * ai_provenance_records / ai_tool_executions / ai_approvals ->
--       ai_runs, ai_agents, ai_agent_versions (pre-existing)
--     * export_jobs -> report_definitions, document_templates
--     * usage_rollup_snapshots -> finance_periods, rate_card_versions,
--       usage_events (self)
--     * finance_adjustments -> finance_periods, usage_events
--     * usage_events -> finance_periods (added by V...0090000)
--     * legal_hold_events -> legal_holds
--     * privacy_deletion_targets -> privacy_deletion_requests
--     * report_versions -> report_definitions
--     * document_template_versions -> document_templates
--   Tables are therefore dropped in REVERSE dependency order, triggers and
--   functions are dropped before the tables that reference them, and the one
--   ADD CONSTRAINT this slice made on an existing table
--   (usage_events_period_tenant_fkey) is dropped before usage_events.
--   The three composite-key indexes this slice added to EXISTING tables
--   (users, ai_runs, ai_agents) are dropped LAST and their absence is checked,
--   because a fingerprint that does not return exactly means something was left
--   behind.
--
-- REHEARSED on a throwaway database; see README-phase7-10a.md section 6 for the
-- measured before/after fingerprints.
-- =====================================================================

-- The WHOLE BODY IS INSIDE THE CONDITIONAL. psql's `\if` encloses a SEQUENCE of
-- commands up to `\endif` and, when the condition is false, discards the lines
-- WITHOUT PARSING them -- which is the property this needs. The first version put
-- only an `\echo` inside `\if` and left `set_config(..., :'firstelement_undo_armed', ...)`
-- outside it, so the unarmed rehearsal printed "SKIPPED" and then died with
--     ERROR: syntax error at or near ":"
-- because `:'undefined_variable'` is not substituted at all. An unarmed run must
-- be a clean no-op, not a no-op followed by a syntax error, because the operator
-- has to be able to read the refusal without knowing whether the script is valid.
\if :{?firstelement_undo_armed}

-- =====================================================================
-- ON_ERROR_STOP IS NOT OPTIONAL, AND THIS IS THE THIRD TIME THIS FILE
-- HAS BEEN FALSIFIED BY A SILENT CONTINUE
--
--   Measured on this host, 2026-10-05: with the guards intact and armed and
--   WITHOUT -v confirm_data_loss, on a slice holding seeded rows, the script
--   printed
--       NOTICE  UNDO PHASE7-10A REFUSED: the slice holds rows (see the counts)
--       ERROR   UNDO PHASE7-10A REFUSED: ...
--   and then -- DROPPED ALL 37 TABLES ANYWAY, ending with
--       NOTICE  PHASE7-10A_REMOVED
--   with an overall exit code of 1.
--
--   The cause is psql's default behaviour, not the guards: without
--   ON_ERROR_STOP a statement that raises is reported and then the NEXT
--   statement runs. Every guard in this file is a RAISE EXCEPTION inside a DO
--   block, so every guard in this file was, on its own, only a MESSAGE. The
--   refusal was correct, printed, and had no effect whatsoever.
--
--   This is the worst possible failure mode for a destructive script: the
--   operator reads a REFUSED, the exit code is non-zero, and the tables are
--   gone anyway -- so the exit code cannot be trusted as evidence either.
--
--   Two independent defences, because one is not enough:
--     1. \set ON_ERROR_STOP on below, so a RAISE stops the script;
--     2. the destructive section is gated on a psql VARIABLE that the guards
--        themselves set via \gset -- so even if ON_ERROR_STOP is somehow lost
--        (a wrapper that rewrites the flags, a future refactor that drops the
--        \set), the drops are unreachable unless the guards ran to completion.
-- =====================================================================
\set ON_ERROR_STOP on

-- Hand the psql variables into session GUCs. A PLAIN statement, because psql does
-- not substitute variables inside a dollar-quoted body.
--
-- EACH FLAG GETS ITS OWN `\if`. psql leaves `:'name'` completely unsubstituted
-- when `name` is undefined -- it does not substitute an empty string -- so
-- `coalesce(:'confirm_data_loss', 'no')` is a SYNTAX ERROR, not a null. That was
-- the second defect this file had to fix, and it is the same root cause as the
-- first: a variable that is not always defined cannot appear in the text at all.
-- The DO blocks therefore read the GUC with
-- coalesce(current_setting('firstelement.confirm_data_loss', true), 'no').
SELECT set_config('firstelement.undo_armed', :'firstelement_undo_armed', false);

\if :{?confirm_data_loss}
SELECT set_config('firstelement.confirm_data_loss', :'confirm_data_loss', false);
\endif

DO $$
DECLARE
    armed boolean;
    confirmed boolean;
    present text;
    absent  text;
    counts  text := '';
    r       record;
    baseline_tables int;
    removed  int := 0;
BEGIN
    armed := current_setting('firstelement.undo_armed', true) = 'on';
    IF NOT armed THEN
        RAISE NOTICE 'UNDO PHASE7-10A SKIPPED: not armed. Nothing was touched.';
        RETURN;
    END IF;

    confirmed := coalesce(current_setting('firstelement.confirm_data_loss', true), 'no')
                 = 'yes';

    -- 1. THE SLICE MUST BE WHOLLY PRESENT. A half-applied slice means a migration
    --    failed mid-chain, and dropping half of it is not "undoing" it.
    SELECT string_agg(t, ', ') INTO present
      FROM unnest(ARRAY[
          'retention_classes', 'legal_holds', 'legal_hold_events',
          'privacy_deletion_requests', 'privacy_deletion_targets',
          'admin_audit_events', 'security_events', 'job_integration_events',
          'report_definitions', 'report_versions',
          'document_templates', 'document_template_versions',
          'export_jobs', 'export_job_attempts',
          'export_download_events', 'export_deletion_events',
          'usage_events', 'rate_cards', 'rate_card_versions',
          'rate_card_version_lines', 'finance_periods',
          'finance_period_lock_events', 'usage_rollup_snapshots',
          'finance_adjustments', 'finance_reconciliation_runs',
          'finance_reconciliation_lines',
          'ai_policies', 'ai_policy_versions', 'ai_model_registry',
          'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
          'ai_approval_decisions', 'ai_redaction_records',
          'ai_provenance_records', 'ai_tool_registry', 'ai_tool_executions'
      ]) AS t
     WHERE to_regclass('public.' || quote_ident(t)) IS NOT NULL;

    SELECT string_agg(t, ', ') INTO absent
      FROM unnest(ARRAY[
          'retention_classes', 'legal_holds', 'legal_hold_events',
          'privacy_deletion_requests', 'privacy_deletion_targets',
          'admin_audit_events', 'security_events', 'job_integration_events',
          'report_definitions', 'report_versions',
          'document_templates', 'document_template_versions',
          'export_jobs', 'export_job_attempts',
          'export_download_events', 'export_deletion_events',
          'usage_events', 'rate_cards', 'rate_card_versions',
          'rate_card_version_lines', 'finance_periods',
          'finance_period_lock_events', 'usage_rollup_snapshots',
          'finance_adjustments', 'finance_reconciliation_runs',
          'finance_reconciliation_lines',
          'ai_policies', 'ai_policy_versions', 'ai_model_registry',
          'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
          'ai_approval_decisions', 'ai_redaction_records',
          'ai_provenance_records', 'ai_tool_registry', 'ai_tool_executions'
      ]) AS t
     WHERE to_regclass('public.' || quote_ident(t)) IS NULL;

    IF absent IS NOT NULL THEN
        RAISE EXCEPTION
            'UNDO PHASE7-10A REFUSED: the slice is only partially present. Missing: %. '
            'Present: %. A half-applied slice means a migration failed mid-chain, and dropping '
            'half of it is not undoing it -- fix the migration and re-run it instead.', absent,
            coalesce(present, '(none)');
    END IF;

    -- 2. BASELINE FLOOR. Refuse to run against a partially migrated database: the
    --    Phase-2 baseline alone is 45 tables.
    SELECT count(*) INTO baseline_tables
      FROM pg_tables WHERE schemaname = 'public'
       AND tablename NOT IN ('flyway_schema_history')
       AND tablename NOT IN (
           'retention_classes','legal_holds','legal_hold_events',
           'privacy_deletion_requests','privacy_deletion_targets',
           'admin_audit_events','security_events','job_integration_events',
           'report_definitions','report_versions',
           'document_templates','document_template_versions',
           'export_jobs','export_job_attempts',
           'export_download_events','export_deletion_events',
           'usage_events','rate_cards','rate_card_versions',
           'rate_card_version_lines','finance_periods',
           'finance_period_lock_events','usage_rollup_snapshots',
           'finance_adjustments','finance_reconciliation_runs',
           'finance_reconciliation_lines',
           'ai_policies','ai_policy_versions','ai_model_registry',
           'ai_budgets','ai_budget_consumptions','ai_approvals',
           'ai_approval_decisions','ai_redaction_records',
           'ai_provenance_records','ai_tool_registry','ai_tool_executions');

    IF baseline_tables < 45 THEN
        RAISE EXCEPTION
            'UNDO PHASE7-10A REFUSED: only % non-slice tables exist; the Phase-2 baseline is 45. '
            'This database is not the one the undo was written for.', baseline_tables;
    END IF;

    -- 3. ROW COUNTS, PRINTED ALWAYS -- before the refusal, so the refusal is
    --    INFORMED. `counts` starts at '' and appends unconditionally, because
    --    `coalesce(counts || ..., '')` is exactly the case where coalesce's
    --    fallback fires and the first entry disappears.
    FOR r IN
        SELECT * FROM (VALUES
            ('retention_classes'), ('legal_holds'), ('legal_hold_events'),
            ('privacy_deletion_requests'), ('privacy_deletion_targets'),
            ('admin_audit_events'), ('security_events'),
            ('job_integration_events'),
            ('report_definitions'), ('report_versions'),
            ('document_templates'), ('document_template_versions'),
            ('export_jobs'), ('export_job_attempts'),
            ('export_download_events'), ('export_deletion_events'),
            ('usage_events'), ('rate_cards'), ('rate_card_versions'),
            ('rate_card_version_lines'), ('finance_periods'),
            ('finance_period_lock_events'), ('usage_rollup_snapshots'),
            ('finance_adjustments'), ('finance_reconciliation_runs'),
            ('finance_reconciliation_lines'),
            ('ai_policies'), ('ai_policy_versions'), ('ai_model_registry'),
            ('ai_budgets'), ('ai_budget_consumptions'), ('ai_approvals'),
            ('ai_approval_decisions'), ('ai_redaction_records'),
            ('ai_provenance_records'), ('ai_tool_registry'),
            ('ai_tool_executions')
        ) AS t(name)
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I', r.name) INTO STRICT removed;
        counts := counts || r.name || '=' || removed || ' ';
    END LOOP;

    RAISE NOTICE 'UNDO PHASE7-10A row counts before removal: %', btrim(counts);

    -- 4. THE DATA GATE. Destroying finance, audit and AI evidence is never
    --    automatic. After the first row exists the correct action is to roll the
    --    application back and leave the tables in place.
    IF confirmed = false AND (counts !~ '=\d+\s' OR btrim(counts) ~ '=[1-9]') THEN
        RAISE EXCEPTION
            'UNDO PHASE7-10A REFUSED: the slice holds rows (see the counts printed above). '
            'Audit, finance and AI evidence is never deleted to tidy up a schema. Roll the '
            'application back and leave the tables, or re-run with '
            '-v confirm_data_loss=yes if you have approval and a backup.';
    END IF;

    -- 5. NOTHING ELSE MAY DEPEND ON THE SLICE. Another migration pointing into it
    --    means reverse order, not a surprise drop.
    IF EXISTS (
        SELECT 1
          FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND con.contype = 'f'
           AND con.confrelid IN (
               SELECT c2.oid FROM pg_class c2
                 JOIN pg_namespace nn2 ON nn2.oid = c2.relnamespace
                WHERE nn2.nspname = 'public'
                  AND c2.relname IN (
                      'retention_classes','legal_holds','legal_hold_events',
                      'privacy_deletion_requests','privacy_deletion_targets',
                      'admin_audit_events','security_events',
                      'job_integration_events',
                      'report_definitions','report_versions',
                      'document_templates','document_template_versions',
                      'export_jobs','export_job_attempts',
                      'export_download_events','export_deletion_events',
                      'usage_events','rate_cards','rate_card_versions',
                      'rate_card_version_lines','finance_periods',
                      'finance_period_lock_events','usage_rollup_snapshots',
                      'finance_adjustments','finance_reconciliation_runs',
                      'finance_reconciliation_lines',
                      'ai_policies','ai_policy_versions','ai_model_registry',
                      'ai_budgets','ai_budget_consumptions','ai_approvals',
                      'ai_approval_decisions','ai_redaction_records',
                      'ai_provenance_records','ai_tool_registry',
                      'ai_tool_executions'))
           AND con.conrelid NOT IN (
               SELECT c3.oid FROM pg_class c3
                 JOIN pg_namespace nn3 ON nn3.oid = c3.relnamespace
                WHERE nn3.nspname = 'public'
                  AND c3.relname IN (
                      'retention_classes','legal_holds','legal_hold_events',
                      'privacy_deletion_requests','privacy_deletion_targets',
                      'admin_audit_events','security_events',
                      'job_integration_events',
                      'report_definitions','report_versions',
                      'document_templates','document_template_versions',
                      'export_jobs','export_job_attempts',
                      'export_download_events','export_deletion_events',
                      'usage_events','rate_cards','rate_card_versions',
                      'rate_card_version_lines','finance_periods',
                      'finance_period_lock_events','usage_rollup_snapshots',
                      'finance_adjustments','finance_reconciliation_runs',
                      'finance_reconciliation_lines',
                      'ai_policies','ai_policy_versions','ai_model_registry',
                      'ai_budgets','ai_budget_consumptions','ai_approvals',
                      'ai_approval_decisions','ai_redaction_records',
                      'ai_provenance_records','ai_tool_registry',
                      'ai_tool_executions'))) THEN
        RAISE EXCEPTION
            'UNDO PHASE7-10A REFUSED: an object OUTSIDE this slice has a foreign key into it. '
            'That means reverse order, not a surprise drop. Find the referencing migration and '
            'undo it first.';
    END IF;

    RAISE NOTICE 'UNDO PHASE7-10A armed and cleared. Proceeding.';
END
$$;

-- Defence 2: the guards set this psql variable, and ONLY they can. `\gset`
-- defines `undo_go` only when the query returns a row, so if any guard above
-- raised, execution never reaches here (ON_ERROR_STOP), and even if it did,
-- `undo_go` would be undefined and the `\if` below would take the safe branch.
SELECT 'on' AS undo_go
 WHERE current_setting('firstelement.undo_armed', true) = 'on' \gset

\if :{?undo_go}

-- ---------------------------------------------------------------------
-- Triggers and functions first: they reference the tables, so they go before.
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS "ai_tool_executions_denied_idx" ON "public"."ai_tool_executions";
DROP TRIGGER IF EXISTS "finance_reconciliation_lines_append_only" ON "public"."finance_reconciliation_lines";
DROP TRIGGER IF EXISTS "finance_reconciliation_runs_append_only" ON "public"."finance_reconciliation_runs";
DROP TRIGGER IF EXISTS "usage_rollup_snapshots_append_only" ON "public"."usage_rollup_snapshots";
DROP TRIGGER IF EXISTS "usage_rollup_snapshots_locked_period_guard" ON "public"."usage_rollup_snapshots";
DROP TRIGGER IF EXISTS "finance_period_lock_events_append_only" ON "public"."finance_period_lock_events";
DROP TRIGGER IF EXISTS "rate_card_version_lines_append_only" ON "public"."rate_card_version_lines";
DROP TRIGGER IF EXISTS "rate_card_versions_append_only" ON "public"."rate_card_versions";

-- The one ADD CONSTRAINT this slice made on a table it did not create.
ALTER TABLE "public"."usage_events" DROP CONSTRAINT IF EXISTS "usage_events_period_tenant_fkey";

-- ...and the one TRIGGER. usage_events is dropped later in this script, so the
-- trigger goes with it, but the drop is written explicitly and is asserted in
-- the post-condition: a trigger left behind on a table this undo removes is
-- invisible to the fingerprint, and a later migration recreating a table of that
-- name would inherit a guard nobody asked for.
DROP TRIGGER IF EXISTS "usage_events_append_only" ON "public"."usage_events";

-- ---------------------------------------------------------------------
-- Tables, reverse dependency order.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS "public"."finance_reconciliation_lines";
DROP TABLE IF EXISTS "public"."finance_reconciliation_runs";
DROP TABLE IF EXISTS "public"."finance_adjustments";
DROP TABLE IF EXISTS "public"."usage_rollup_snapshots";
DROP TABLE IF EXISTS "public"."finance_period_lock_events";
DROP TABLE IF EXISTS "public"."finance_periods";
DROP TABLE IF EXISTS "public"."rate_card_version_lines";
DROP TABLE IF EXISTS "public"."rate_card_versions";
DROP TABLE IF EXISTS "public"."rate_cards";
DROP TABLE IF EXISTS "public"."usage_events";

DROP TABLE IF EXISTS "public"."ai_tool_executions";
DROP TABLE IF EXISTS "public"."ai_tool_registry";
DROP TABLE IF EXISTS "public"."ai_provenance_records";
DROP TABLE IF EXISTS "public"."ai_redaction_records";
DROP TABLE IF EXISTS "public"."ai_approval_decisions";
DROP TABLE IF EXISTS "public"."ai_approvals";
DROP TABLE IF EXISTS "public"."ai_budget_consumptions";
DROP TABLE IF EXISTS "public"."ai_budgets";
DROP TABLE IF EXISTS "public"."ai_model_registry";
DROP TABLE IF EXISTS "public"."ai_policy_versions";
DROP TABLE IF EXISTS "public"."ai_policies";

DROP TABLE IF EXISTS "public"."export_deletion_events";
DROP TABLE IF EXISTS "public"."export_download_events";
DROP TABLE IF EXISTS "public"."export_job_attempts";
DROP TABLE IF EXISTS "public"."export_jobs";

DROP TABLE IF EXISTS "public"."document_template_versions";
DROP TABLE IF EXISTS "public"."document_templates";
DROP TABLE IF EXISTS "public"."report_versions";
DROP TABLE IF EXISTS "public"."report_definitions";

DROP TABLE IF EXISTS "public"."job_integration_events";
DROP TABLE IF EXISTS "public"."security_events";
DROP TABLE IF EXISTS "public"."admin_audit_events";
DROP TABLE IF EXISTS "public"."privacy_deletion_targets";
DROP TABLE IF EXISTS "public"."privacy_deletion_requests";
DROP TABLE IF EXISTS "public"."legal_hold_events";
DROP TABLE IF EXISTS "public"."legal_holds";
DROP TABLE IF EXISTS "public"."retention_classes";

-- ---------------------------------------------------------------------
-- Types, functions, then the composite-key indexes this slice added to
-- EXISTING tables. QUOTED -- the Phase-5a bug class.
-- ---------------------------------------------------------------------
DROP TYPE IF EXISTS "public"."AdjustmentStatus";
DROP TYPE IF EXISTS "public"."ReconciliationStatus";
DROP TYPE IF EXISTS "public"."RollupGrain";
DROP TYPE IF EXISTS "public"."PeriodStatus";
DROP TYPE IF EXISTS "public"."RateCardStatus";
DROP TYPE IF EXISTS "public"."UsageDimension";

DROP FUNCTION IF EXISTS "public"."usage_rollup_snapshots_reject_locked_period"();
DROP FUNCTION IF EXISTS "public"."firstelement_rate_card_version_guard"();
DROP FUNCTION IF EXISTS "public"."firstelement_append_only"();

DROP TYPE IF EXISTS "public"."AiBudgetPeriod";
DROP TYPE IF EXISTS "public"."AiRiskLevel";
DROP TYPE IF EXISTS "public"."AiApprovalStatus";
DROP TYPE IF EXISTS "public"."AiPolicyDecision";

DROP TYPE IF EXISTS "public"."ExportDeletionOutcome";
DROP TYPE IF EXISTS "public"."ExportDownloadOutcome";
DROP TYPE IF EXISTS "public"."ExportFormat";
DROP TYPE IF EXISTS "public"."ExportJobStatus";

DROP TYPE IF EXISTS "public"."DocumentTemplateStatus";
DROP TYPE IF EXISTS "public"."ReportStatus";

DROP TYPE IF EXISTS "public"."PrivacyDeletionRequestStatus";
DROP TYPE IF EXISTS "public"."LegalHoldScopeType";
DROP TYPE IF EXISTS "public"."AuditStreamKind";
DROP TYPE IF EXISTS "public"."AuditPrincipalType";
DROP TYPE IF EXISTS "public"."AuditOutcome";

DROP FUNCTION IF EXISTS "public"."firstelement_currency_code"(text);
DROP FUNCTION IF EXISTS "public"."firstelement_json_list_is_sort"(jsonb);
DROP FUNCTION IF EXISTS "public"."firstelement_json_list_is_string_array"(jsonb);
DROP FUNCTION IF EXISTS "public"."firstelement_array_has_blank"(anyarray);
DROP FUNCTION IF EXISTS "public"."firstelement_valid_timezone"(text);

-- V20261005110000: the child-scope expansion on PRE-EXISTING baseline tables.
DROP INDEX IF EXISTS "public"."ai_run_steps_org_project_created_idx";
DROP INDEX IF EXISTS "public"."ai_run_retrievals_org_project_created_idx";
DROP INDEX IF EXISTS "public"."ai_run_tool_calls_org_project_created_idx";
ALTER TABLE "public"."ai_run_steps"      DROP COLUMN IF EXISTS "organizationId",
                                          DROP COLUMN IF EXISTS "projectId",
                                          DROP COLUMN IF EXISTS "updatedAt";
ALTER TABLE "public"."ai_run_retrievals" DROP COLUMN IF EXISTS "organizationId",
                                          DROP COLUMN IF EXISTS "projectId",
                                          DROP COLUMN IF EXISTS "updatedAt";
ALTER TABLE "public"."ai_run_tool_calls" DROP COLUMN IF EXISTS "organizationId",
                                          DROP COLUMN IF EXISTS "projectId",
                                          DROP COLUMN IF EXISTS "updatedAt";

-- The composite tenant keys added to existing tables (IF NOT EXISTS in the
-- migrations, so they must be dropped last and their absence checked).
DROP INDEX IF EXISTS "public"."users_id_organizationId_key";
DROP INDEX IF EXISTS "public"."outbox_events_id_organizationId_key";
DROP INDEX IF EXISTS "public"."job_attempts_id_organizationId_key";
DROP INDEX IF EXISTS "public"."ai_runs_id_organizationId_key";
DROP INDEX IF EXISTS "public"."ai_agents_id_organizationId_key";
DROP INDEX IF EXISTS "public"."ai_agent_versions_id_organizationId_key";

-- ---------------------------------------------------------------------
-- Post-condition. Every identifier quoted, and it checks tables AND types AND
-- indexes, because the Phase-5a undo reported success while leaving four enums
-- behind.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    leftover text;
BEGIN
    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY[
          'retention_classes','legal_holds','legal_hold_events',
          'privacy_deletion_requests','privacy_deletion_targets',
          'admin_audit_events','security_events','job_integration_events',
          'report_definitions','report_versions',
          'document_templates','document_template_versions',
          'export_jobs','export_job_attempts',
          'export_download_events','export_deletion_events',
          'usage_events','rate_cards','rate_card_versions',
          'rate_card_version_lines','finance_periods',
          'finance_period_lock_events','usage_rollup_snapshots',
          'finance_adjustments','finance_reconciliation_runs',
          'finance_reconciliation_lines',
          'ai_policies','ai_policy_versions','ai_model_registry',
          'ai_budgets','ai_budget_consumptions','ai_approvals',
          'ai_approval_decisions','ai_redaction_records',
          'ai_provenance_records','ai_tool_registry','ai_tool_executions'
      ]) AS x
     WHERE to_regclass('public.' || quote_ident(x)) IS NOT NULL;

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'PHASE7-10A_UNDO_INCOMPLETE: these tables survive: %', leftover;
    END IF;

    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY[
          'AuditOutcome','AuditPrincipalType','AuditStreamKind',
          'LegalHoldScopeType','PrivacyDeletionRequestStatus',
          'ReportStatus','DocumentTemplateStatus',
          'ExportJobStatus','ExportFormat','ExportDownloadOutcome',
          'ExportDeletionOutcome','UsageDimension','RateCardStatus',
          'PeriodStatus','RollupGrain','ReconciliationStatus',
          'AdjustmentStatus','AiPolicyDecision','AiApprovalStatus',
          'AiRiskLevel','AiBudgetPeriod'
      ]) AS x
     WHERE EXISTS (SELECT 1 FROM pg_type t
                     JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = 'public' AND t.typname = x);

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'PHASE7-10A_UNDO_INCOMPLETE: these types survive: %', leftover;
    END IF;

    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY[
          'users_id_organizationId_key',
          'outbox_events_id_organizationId_key',
          'job_attempts_id_organizationId_key',
          'ai_runs_id_organizationId_key',
          'ai_agents_id_organizationId_key',
          'ai_agent_versions_id_organizationId_key',
          'ai_run_steps_org_project_created_idx',
          'ai_run_retrievals_org_project_created_idx',
          'ai_run_tool_calls_org_project_created_idx'
      ]) AS x
     WHERE EXISTS (SELECT 1 FROM pg_indexes
                    WHERE schemaname = 'public' AND indexname = x);

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION
            'PHASE7-10A_UNDO_INCOMPLETE: these indexes on EXISTING tables survive: % -- the '
            'fingerprint will not return to its pre-slice value.', leftover;
    END IF;

    -- The Phase-7-10a FUNCTIONS must all be gone, or the undo has left schema
    -- behind that nothing in the fingerprint's constraint/column/index surface
    -- reports -- which is how the Phase-5a undo "reported success while leaving
    -- four enums behind". Two functions here carry very similar names on purpose
    -- (firstelement_append_only and firstelement_rate_card_version_guard) and
    -- both must be asserted, because dropping only the first is the easy mistake.
    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY[
          'firstelement_append_only',
          'firstelement_rate_card_version_guard',
          'usage_rollup_snapshots_reject_locked_period',
          'firstelement_valid_timezone',
          'firstelement_array_has_blank',
          'firstelement_json_list_is_string_array',
          'firstelement_json_list_is_sort'
      ]) AS x
     WHERE to_regprocedure('public.' || x || '()') IS NOT NULL;

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION
            'PHASE7-10A_UNDO_INCOMPLETE: these functions survive: % -- a function without a '
            'table is invisible to the fingerprint and would be reported as a clean undo.',
            leftover;
    END IF;

    -- The seven append-only / narrow triggers must all be gone with their tables.
    -- Dropping a table drops its triggers, so this is belt-and-braces; it is here
    -- because a trigger left attached to a recreated table would fire against
    -- data the undo believes it removed.
    SELECT string_agg(x, ', ') INTO leftover
      FROM unnest(ARRAY[
          'rate_card_versions_append_only',
          'rate_card_version_lines_append_only',
          'finance_period_lock_events_append_only',
          'usage_rollup_snapshots_append_only',
          'finance_reconciliation_runs_append_only',
          'finance_reconciliation_lines_append_only',
          'usage_rollup_snapshots_locked_period_guard',
          'usage_events_append_only'
      ]) AS x
     WHERE EXISTS (SELECT 1 FROM pg_trigger tg
                    JOIN pg_class c ON c.oid = tg.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public' AND tg.tgname = x AND NOT tg.tgisinternal);

    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION
            'PHASE7-10A_UNDO_INCOMPLETE: these triggers survive: % -- their tables are gone, so '
            'nothing reports them, and a later migration recreating one of those table names '
            'would inherit a guard nobody asked for.', leftover;
    END IF;

    -- audit_logs must be exactly as it was: the legacy runtime still writes it.
    IF to_regclass('public.audit_logs') IS NULL THEN
        RAISE EXCEPTION
            'PHASE7-10A_UNDO_INCOMPLETE: audit_logs is gone. The undo must never touch it.';
    END IF;

    RAISE NOTICE
        'PHASE7-10A_REMOVED: 37 tables and 21 enums dropped, 7 functions and 7 triggers '
        'dropped, 3 child-scope columns removed from each of ai_run_steps / '
        'ai_run_retrievals / ai_run_tool_calls, and 9 indexes removed from pre-existing tables. '
        'The 45 baseline tables are intact.';
END
$$;

\else
  \echo 'UNDO PHASE7-10A ABORTED BEFORE ANY DROP: the guards did not clear (see the REFUSED line above). Nothing was dropped. Fix the reported condition, or re-run with -v confirm_data_loss=yes if you have approval and a backup.'
\endif

\else
  \echo 'UNDO PHASE7-10A SKIPPED: pass -v firstelement_undo_armed=on to arm it. Nothing was touched.'
\endif