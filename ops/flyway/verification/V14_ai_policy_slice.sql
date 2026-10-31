-- =====================================================================
-- V14_ai_policy_slice.sql
-- Phase 7-10a / database verification: the AI policy and governance slice
-- introduced by V20261005100000, plus the child-scope expansion introduced by
-- V20261005110000 (the non-transactional CONCURRENTLY migration).
--
-- Two things are proved here that no other file can:
--   * DENY BY DEFAULT IS A PROPERTY OF THE SCHEMA. Section 3 asserts the literal
--     DEFAULT expression is false and that a disabled tool is storable as
--     disabled. ADR-0007 Part 2: "Forbidden to the model: ... calling a
--     disabled tool ... denied and recorded, always."
--   * AN APPROVAL RECORD MUST EXIST FOR ANY CONSEQUENTIAL ACTION. Section 6
--     asserts, with data, that no consequential execution ran without naming
--     its approval.
--
-- READ-ONLY. No DDL, no DML, no object created. Guarded: on the pre-cutover
-- database each section reports PHASE710A_NOT_PRESENT.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Presence.
-- ---------------------------------------------------------------------
SELECT CASE
         WHEN to_regclass('public.ai_tool_registry') IS NOT NULL
          AND to_regclass('public.ai_tool_executions') IS NOT NULL
          AND to_regclass('public.ai_policies') IS NOT NULL
          AND to_regclass('public.ai_approvals') IS NOT NULL
          AND to_regclass('public.ai_provenance_records') IS NOT NULL
         THEN 'PHASE710A_AI_PRESENT'
         WHEN to_regclass('public.ai_tool_registry') IS NULL
          AND to_regclass('public.ai_tool_executions') IS NULL
          AND to_regclass('public.ai_policies') IS NULL
          AND to_regclass('public.ai_approvals') IS NULL
          AND to_regclass('public.ai_provenance_records') IS NULL
         THEN 'PHASE710A_NOT_PRESENT'
         ELSE 'PHASE710A_PARTIAL -- a Phase-7-10a AI migration failed halfway; stop'
       END AS phase7_10a_ai_state;

-- ---------------------------------------------------------------------
-- 2. Enum value lists.
-- ---------------------------------------------------------------------
SELECT t.typname AS enum_name,
       string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) AS enum_values
  FROM pg_type t
  JOIN pg_enum e ON e.enumtypid = t.oid
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
   AND t.typname IN ('AiPolicyDecision', 'AiApprovalStatus', 'AiRiskLevel',
                     'AiBudgetPeriod')
 GROUP BY t.typname
 ORDER BY t.typname;

-- ---------------------------------------------------------------------
-- 3. DENY BY DEFAULT IS LITERAL. The DEFAULT EXPRESSION, not "no row happens to
--    be enabled": flipping the default would break every existing deployment's
--    assumption at the same instant, and that is exactly the change the guard
--    exists to make visible.
-- ---------------------------------------------------------------------
SELECT a.attname AS column_name,
       format_type(a.atttypid, a.atttypmod) AS data_type,
       CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END AS nullable,
       COALESCE(pg_get_expr(d.adbin, d.adrelid), '-') AS default_expr,
       COALESCE(pg_get_expr(d.adbin, d.adrelid), '-') = 'false' AS is_deny_by_default
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
 WHERE n.nspname = 'public'
   AND c.relname = 'ai_tool_registry'
   AND a.attname IN ('isEnabled', 'disabledAt', 'disabledByUserId', 'disabledReason',
                     'enabledAt', 'enabledByUserId', 'fieldAllowList', 'riskLevel',
                     'consequential', 'requiredPermission')
 ORDER BY a.attnum;

-- 3a. THE LIVE STATE OF THE TOOL REGISTRY. A disabled tool is storable as
--     disabled, and this is what that looks like with rows.
--     Guarded with dynamic SQL, because a plain SELECT naming a dropped table is
--     a PARSE-time error and the bundle has to report PHASE710A_NOT_PRESENT after
--     an undo rather than fail. Found by the post-undo rehearsal.
DO $$
DECLARE
    rec record;
BEGIN
    IF to_regclass('public.ai_tool_registry') IS NULL THEN
        RAISE NOTICE 'V14 3a: PHASE710A_NOT_PRESENT';
        RETURN;
    END IF;
    FOR rec IN EXECUTE $q$
        select "toolName", "riskLevel"::text, "consequential", "isEnabled",
               ("disabledAt" is not null) as records_the_disable,
               coalesce(array_length("fieldAllowList", 1), 0) as allow_list_size,
               "requiredPermission"
          from public.ai_tool_registry
         order by "toolName"
    $q$
    LOOP
        RAISE NOTICE
            'V14 TOOL %: risk=% consequential=% enabled=% records_the_disable=% allow_list=% permission=%',
            rec."toolName", rec."riskLevel", rec."consequential", rec."isEnabled",
            rec."records_the_disable", rec."allow_list_size", rec."requiredPermission";
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 4. Summary, tenant scope, timestamps, append-only boundary.
-- ---------------------------------------------------------------------
SELECT (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history') AS tables,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c') AS check_constraints,
       (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND con.contype = 'c'
           AND c.relname IN ('ai_policies', 'ai_policy_versions',
                             'ai_model_registry', 'ai_budgets',
                             'ai_budget_consumptions', 'ai_approvals',
                             'ai_approval_decisions', 'ai_redaction_records',
                             'ai_provenance_records', 'ai_tool_registry',
                             'ai_tool_executions')) AS ai_checks;

-- 4a. FR-1.2. Must be 0.
SELECT count(*) AS nullable_organization_id
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('ai_policies', 'ai_policy_versions', 'ai_model_registry',
                     'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
                     'ai_approval_decisions', 'ai_redaction_records',
                     'ai_provenance_records', 'ai_tool_registry',
                     'ai_tool_executions')
   AND a.attname = 'organizationId' AND NOT a.attnotnull;

-- 4b. V6 contract. Must be 0.
SELECT count(*) AS timestamptz_columns
  FROM pg_attribute a
  JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('ai_policies', 'ai_policy_versions', 'ai_model_registry',
                     'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
                     'ai_approval_decisions', 'ai_redaction_records',
                     'ai_provenance_records', 'ai_tool_registry',
                     'ai_tool_executions')
   AND a.attnum > 0 AND NOT a.attisdropped
   AND a.atttypid = 'timestamptz'::regtype;

-- 4c. THE NULLABLE projectId LIST FOR THE AI SLICE. Expected exactly:
--     ai_model_registry, ai_provenance_records, ai_redaction_records,
--     ai_tool_registry. NOT in the list: ai_policies, ai_policy_versions,
--     ai_budgets, ai_budget_consumptions, ai_approvals,
--     ai_approval_decisions and ai_tool_executions are all NOT NULL.
SELECT c.relname AS table_name
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0
                      AND NOT a.attisdropped AND a.attname = 'projectId'
 WHERE n.nspname = 'public'
   AND c.relname IN ('ai_policies', 'ai_policy_versions', 'ai_model_registry',
                     'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
                     'ai_approval_decisions', 'ai_redaction_records',
                     'ai_provenance_records', 'ai_tool_registry',
                     'ai_tool_executions')
   AND NOT a.attnotnull
 ORDER BY c.relname;

-- 4d. THE APPEND-ONLY BOUNDARY. Expected exactly:
--     ai_policy_versions / ai_budget_consumptions / ai_approval_decisions /
--     ai_redaction_records / ai_provenance_records / ai_tool_executions
--         = append_only
--     ai_policies / ai_model_registry / ai_budgets / ai_approvals /
--     ai_tool_registry = mutable
SELECT c.relname || '=' || CASE WHEN EXISTS (
           SELECT 1 FROM pg_attribute a
            WHERE a.attrelid = c.oid AND a.attnum > 0
              AND NOT a.attisdropped AND a.attname = 'updatedAt')
         THEN 'mutable' ELSE 'append_only' END AS boundary
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND c.relname IN ('ai_policies', 'ai_policy_versions', 'ai_model_registry',
                     'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
                     'ai_approval_decisions', 'ai_redaction_records',
                     'ai_provenance_records', 'ai_tool_registry',
                     'ai_tool_executions')
 ORDER BY 1;

-- 4e. NO SECRETS, NO RAW CONTENT, NO CHAIN-OF-THOUGHT. All three must return 0
--     rows. FR-10.6 permits only the redacted form; FR-5.4 and ADR-0007 rule 4
--     forbid storing internal reasoning; and the model registry is a registry.
SELECT c.relname AS table_name, a.attname AS column_name,
       CASE WHEN lower(a.attname) IN ('apikey', 'api_key', 'token', 'secret',
                                      'password', 'clientsecret', 'accesstoken',
                                      'bearertoken', 'privatekey', 'credential',
                                      'credentials')
            THEN 'credential'
            WHEN lower(a.attname) LIKE '%raw%'
              OR lower(a.attname) LIKE '%original%'
              OR lower(a.attname) LIKE '%unredacted%' THEN 'unredacted-content'
            WHEN lower(a.attname) LIKE '%chainofthought%'
              OR lower(a.attname) LIKE '%thinking%'
              OR lower(a.attname) LIKE '%reasoning%'
              OR lower(a.attname) LIKE '%scratchpad%'
              OR lower(a.attname) LIKE '%monologue%'
              OR lower(a.attname) LIKE '%deliberation%' THEN 'chain-of-thought'
       END AS violation
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE n.nspname = 'public'
   AND c.relname IN ('ai_policies', 'ai_policy_versions', 'ai_model_registry',
                     'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
                     'ai_approval_decisions', 'ai_redaction_records',
                     'ai_provenance_records', 'ai_tool_registry',
                     'ai_tool_executions')
   AND (lower(a.attname) IN ('apikey', 'api_key', 'token', 'secret', 'password',
                             'clientsecret', 'accesstoken', 'bearertoken',
                             'privatekey', 'credential', 'credentials')
        OR lower(a.attname) LIKE '%raw%'
        OR lower(a.attname) LIKE '%original%'
        OR lower(a.attname) LIKE '%unredacted%'
        OR lower(a.attname) LIKE '%chainofthought%'
        OR lower(a.attname) LIKE '%thinking%'
        OR lower(a.attname) LIKE '%reasoning%'
        OR lower(a.attname) LIKE '%scratchpad%'
        OR lower(a.attname) LIKE '%monologue%'
        OR lower(a.attname) LIKE '%deliberation%')
 ORDER BY 1, 2;

-- 4f. THE PARTIAL "ONE APPROVED POLICY VERSION" AND "ONE PENDING APPROVAL"
--     INDEXES MUST BE PARTIAL. Without the WHERE clause the first would forbid
--     authoring a second policy version -- the authoring deadlock Phase 6a found
--     and fixed by adding a DRAFT state -- and the second would refuse a
--     legitimate re-request after a rejection.
SELECT c.relname AS index_name, i.indisunique, i.indpred IS NOT NULL AS is_partial,
       t.relname AS on_table
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_class t ON t.oid = i.indrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('ai_policy_versions_one_approved_per_policy',
                     'ai_approvals_one_pending_per_run_action',
                     'ai_provenance_records_run_key')
 ORDER BY 1;

-- ---------------------------------------------------------------------
-- 5. THE CONCURRENTLY MIGRATION. V20261005110000 is the ONE non-transactional
--    step in the chain, because CREATE INDEX CONCURRENTLY cannot run inside a
--    transaction block. Its three indexes must exist AND be VALID: an
--    interrupted concurrent build leaves an index that exists and is invalid,
--    which the planner silently ignores, so the tenant filter quietly becomes a
--    sequential scan.
-- ---------------------------------------------------------------------
SELECT c.relname AS index_name, i.indisvalid, i.indisready,
       t.relname AS on_table,
       pg_get_indexdef(i.indexrelid) AS indexdef
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_class t ON t.oid = i.indrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('ai_run_steps_org_project_created_idx',
                     'ai_run_retrievals_org_project_created_idx',
                     'ai_run_tool_calls_org_project_created_idx')
 ORDER BY 1;

-- 5a/5b/5c. THE CHILD-SCOPE EXPANSION, THE RESIDUAL GAPS B1 must close before the
--     contract step, AND THE CROSS-CHECK THAT NO CHILD ROW DISAGREES WITH ITS
--     PARENT RUN.
--
--     All three are written with EXECUTE inside one guarded block rather than as
--     plain SELECTs, for two reasons that are each a defect only a real run finds:
--       * the columns do not exist before V20261005110000, and a plain SELECT
--         naming them is a PARSE-time error, so the bundle would fail on the
--         pre-cutover database instead of reporting PHASE710A_NOT_PRESENT;
--       * format() is a SQL-level function and cannot be used inside an
--         expression, so the first version -- which tried
--         `SELECT format('...', t, (SELECT count(*) FROM public.%I))` -- failed
--         with `syntax error at or near "%"`. EXECUTE ... INTO is the form that
--         works.
--
--     The disagreement check is a JOIN and not an FK on purpose: the value is
--     DERIVED from ai_runs, and an FK would let a writer set a scope that
--     disagrees with its parent, which is the cross-tenant inconsistency FR-1.4
--     forbids. So the schema permits the column and the assertion refuses the
--     disagreement.
--
--     On a migrated-but-not-backfilled database the missing counts are NON-ZERO
--     and that is CORRECT: it is the window between the migration and B1. They
--     are printed rather than asserted, because asserting zero would fail the
--     bundle in exactly that window.
DO $$
DECLARE
    child_scope_expanded boolean;
    t                    text;
    total                bigint;
    missing_org          bigint;
    missing_proj         bigint;
    line                 text;
BEGIN
    SELECT EXISTS (SELECT 1 FROM pg_attribute a
                     JOIN pg_class c ON c.oid = a.attrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = 'ai_run_steps'
                      AND a.attname = 'organizationId'
                      AND a.attnum > 0 AND NOT a.attisdropped)
      INTO child_scope_expanded;

    IF NOT child_scope_expanded THEN
        RAISE NOTICE 'V14 5a-5b: PHASE710A_NOT_PRESENT (the child-scope expansion has not run)';
        RETURN;
    END IF;

    FOREACH t IN ARRAY ARRAY['ai_run_steps', 'ai_run_retrievals', 'ai_run_tool_calls'] LOOP
        EXECUTE format('select coalesce(pg_get_expr(d.adbin, d.adrelid), ''-'')
                          from pg_catalog.pg_attribute a
                          join pg_catalog.pg_attrdef d
                            on d.adrelid = a.attrelid and d.adnum = a.attnum
                          where a.attrelid = %L::regclass
                            and a.attname = ''updatedAt''', 'public.' || t)
          INTO line;
        RAISE NOTICE 'V14 updatedAt default on %: %', t, line;
    END LOOP;

    FOREACH t IN ARRAY ARRAY['ai_run_steps', 'ai_run_retrievals', 'ai_run_tool_calls'] LOOP
        EXECUTE format(
            'select count(*),
                    count(*) filter (where "organizationId" is null),
                    count(*) filter (where "projectId" is null)
               from public.%I', t)
          INTO total, missing_org, missing_proj;
        RAISE NOTICE
            'V14 scope gap %: total=% missing_organizationId=% missing_projectId=% '
            '(B1 must close these before the contract step)',
            t, total, missing_org, missing_proj;
    END LOOP;
FOREACH t IN ARRAY ARRAY['ai_run_steps', 'ai_run_retrievals', 'ai_run_tool_calls'] LOOP
        EXECUTE format(
            'select count(*) from public.%I s
               join public.ai_runs r on r.id = s."runId"
              where s."organizationId" is not null
                and s."organizationId" <> r."organizationId"', t)
          INTO total;
        RAISE NOTICE
            'V14 % rows disagreeing with their parent ai_runs scope: % (must be 0 -- the value '
            'is derived from the parent, asserted here because no FK may enforce it)', t, total;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 6. CHECK invariants with data. The load-bearing one is the approval
--    biconditional: a CONSEQUENTIAL action that RAN must name its approval.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    executions  bigint;
    tools       bigint;
    approvals   bigint;
    crossed_run bigint;
    crossed_org bigint;
BEGIN
    IF to_regclass('public.ai_tool_executions') IS NULL THEN
        RAISE NOTICE 'V14 section 6: PHASE710A_NOT_PRESENT';
        RETURN;
    END IF;

    SELECT count(*) INTO tools       FROM public.ai_tool_registry;
    SELECT count(*) INTO executions  FROM public.ai_tool_executions;
    SELECT count(*) INTO approvals   FROM public.ai_approvals;
    RAISE NOTICE 'V14 volume: tools=% executions=% approvals=%', tools, executions, approvals;

    -- Cross-tenant negatives, WITH DATA.
    SELECT count(*) INTO crossed_org
      FROM public.ai_tool_executions e
     WHERE NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.id = e."organizationId");

    SELECT count(*) INTO crossed_run
      FROM public.ai_tool_executions e
      JOIN public.ai_runs r ON r.id = e."aiRunId"
     WHERE r."organizationId" <> e."organizationId";

    RAISE NOTICE 'V14 tenancy: unknown_organization=% cross_tenant_ai_run=%',
                 crossed_org, crossed_run;

    -- THE APPROVAL BICONDITIONAL. Three independent halves, each of which was
    -- missing in some version of the design:
    --   a) a consequential action that ran MUST name its approval;
    --   b) an action awaiting approval must NOT have run;
    --   c) a denied action must not have run, and must say why.
    IF EXISTS (SELECT 1 FROM public.ai_tool_executions
                WHERE "consequentialAction"
                  AND "decision" = 'ALLOW'::"public"."AiPolicyDecision"
                  AND "approvalId" IS NULL) THEN
        RAISE EXCEPTION
            'V14: a CONSEQUENTIAL action was ALLOWed and executed with NO approval record. '
            'ADR-0007 Part 2: consequential actions require human approval.';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_tool_executions
                WHERE "decision" = 'REQUIRE_APPROVAL'::"public"."AiPolicyDecision"
                  AND ("executedAt" IS NOT NULL OR "approvalId" IS NULL)) THEN
        RAISE EXCEPTION
            'V14: an action requiring approval either ran anyway or names no approval';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_tool_executions
                WHERE "decision" = 'DENY'::"public"."AiPolicyDecision"
                  AND ("executedAt" IS NOT NULL OR "denyReason" IS NULL
                       OR length(btrim("denyReason")) = 0)) THEN
        RAISE EXCEPTION 'V14: a denied tool execution ran, or does not say why';
    END IF;

    -- A FORBIDDEN action is denied and never ran, whatever the decision column
    -- claims.
    IF EXISTS (SELECT 1 FROM public.ai_tool_executions
                WHERE "actionClass" = 'FORBIDDEN'
                  AND ("decision" <> 'DENY'::"public"."AiPolicyDecision"
                       OR "executedAt" IS NOT NULL)) THEN
        RAISE EXCEPTION
            'V14: a FORBIDDEN action was not denied, or was denied after running';
    END IF;

    -- actionClass and consequentialAction are two copies of one fact and must
    -- never disagree.
    IF EXISTS (SELECT 1 FROM public.ai_tool_executions
                WHERE "consequentialAction" IS DISTINCT FROM
                      ("actionClass" = 'CONSEQUENTIAL')) THEN
        RAISE EXCEPTION
            'V14: an execution''s actionClass and consequentialAction disagree -- two copies '
            'of one fact is how a consequential action runs without an approval';
    END IF;

    -- A disabled tool that was called was REFUSED, never executed.
    IF EXISTS (SELECT 1 FROM public.ai_tool_executions e
                JOIN public.ai_tool_registry t
                  ON t."toolName" = e."toolName"
                 AND t."organizationId" = e."organizationId"
                WHERE NOT t."isEnabled"
                  AND e."executedAt" IS NOT NULL) THEN
        RAISE EXCEPTION
            'V14: a DISABLED tool was executed. ADR-0007 Part 2: calling a disabled tool is '
            'forbidden to the model, denied and recorded, always.';
    END IF;

    -- Every refusal is RECORDED. "Denied and recorded, always" means the DENY rows
    -- exist; this is the acceptance number for the Phase-7 Admin surface.
    IF executions > 0 THEN
        RAISE NOTICE 'V14 decisions: allow=% require_approval=% deny=%',
            (SELECT count(*) FROM public.ai_tool_executions
              WHERE "decision" = 'ALLOW'::"public"."AiPolicyDecision"),
            (SELECT count(*) FROM public.ai_tool_executions
              WHERE "decision" = 'REQUIRE_APPROVAL'::"public"."AiPolicyDecision"),
            (SELECT count(*) FROM public.ai_tool_executions
              WHERE "decision" = 'DENY'::"public"."AiPolicyDecision");
    END IF;

    -- The registry itself: a disabled tool is storable as disabled, with who, when
    -- and why.
    IF EXISTS (SELECT 1 FROM public.ai_tool_registry
                WHERE (NOT "isEnabled")
                      IS DISTINCT FROM ("disabledAt" IS NOT NULL
                                        AND "disabledByUserId" IS NOT NULL
                                        AND "disabledReason" IS NOT NULL)) THEN
        RAISE EXCEPTION
            'V14: a tool''s enabled flag and its disabled-audit columns disagree -- a disabled '
            'tool must be STORABLE AS DISABLED, with the record of who and why';
    END IF;

    -- A HIGH or CRITICAL tool may never be enabled with an empty allow-list.
    IF EXISTS (SELECT 1 FROM public.ai_tool_registry
                WHERE "isEnabled"
                  AND "riskLevel" IN ('HIGH'::"public"."AiRiskLevel",
                                      'CRITICAL'::"public"."AiRiskLevel")
                  AND coalesce(array_length("fieldAllowList", 1), 0) = 0) THEN
        RAISE EXCEPTION
            'V14: a HIGH/CRITICAL tool is enabled with no field allow-list, which means '
            '"may read anything" rather than field-limited';
    END IF;

    -- Approvals: only a CONSEQUENTIAL action is ever approvable, nobody approves
    -- their own request, and a revocation says why.
    IF EXISTS (SELECT 1 FROM public.ai_approvals WHERE "actionClass" <> 'CONSEQUENTIAL') THEN
        RAISE EXCEPTION
            'V14: an approval exists for a non-consequential action -- ANSWERABLE and FORBIDDEN '
            'actions are recorded in ai_tool_executions, not in the approval queue';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_approvals
                WHERE "decidedByUserId" IS NOT NULL
                  AND "requestedByUserId" IS NOT NULL
                  AND "decidedByUserId" = "requestedByUserId") THEN
        RAISE EXCEPTION 'V14: a self-approved AI action';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_approvals
                WHERE ("status" = 'REVOKED'::"public"."AiApprovalStatus")
                      <> ("revokedAt" IS NOT NULL AND "revokedByUserId" IS NOT NULL
                          AND "revokeReason" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V14: a revoked approval does not say who, when or why';
    END IF;

    -- The CORRECTED decision biconditional, as installed by
    -- V20261006100000. Before that migration this read
    --     ("status" = 'APPROVED') = ("decidedAt" IS NOT NULL)
    -- which is a WEAKER rule: it says nothing about REJECTED or REVOKED, so a
    -- rejection with no decider and a revocation that erased its grant's
    -- decidedAt both passed it. The assertion below is the whole point of
    -- ADR-0011 section 1: REJECTED and REVOKED each say who and when, and
    -- PENDING / EXPIRED carry no decision at all -- an EXPIRED request was
    -- never decided by anyone, and requiring a decider on it would invent one.
    IF EXISTS (SELECT 1 FROM public.ai_approvals
                WHERE (status IN ('APPROVED'::"public"."AiApprovalStatus",
                                  'REJECTED'::"public"."AiApprovalStatus",
                                  'REVOKED'::"public"."AiApprovalStatus"))
                      IS DISTINCT FROM ("decidedAt" IS NOT NULL)) THEN
        RAISE EXCEPTION
            'V14: an approval''s status and its decision record disagree under the CORRECTED '
            'biconditional (APPROVED / REJECTED / REVOKED must carry a decision; PENDING / EXPIRED '
            'must not). V20261006100000 installs this rule; see ADR-0011 section 1.';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_approvals
                WHERE "status" = 'REJECTED'::"public"."AiApprovalStatus"
                  AND ("decidedAt" IS NULL OR "decidedByUserId" IS NULL)) THEN
        RAISE EXCEPTION
            'V14: a REJECTED approval hides who refused it. ai_approvals_rejection_check demands a '
            'reason on every rejection and ai_approvals_approval_check (V20261006100000) demands the '
            'decision record -- a rejection nobody can name is a rejection nobody can learn from.';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_approvals
                WHERE "status" = 'REVOKED'::"public"."AiApprovalStatus"
                  AND ("decidedAt" IS NULL OR "decidedByUserId" IS NULL)) THEN
        RAISE EXCEPTION
            'V14: a REVOKED approval erased its grant''s decision record. Revocation changes the '
            'status; it does not un-decide the request.';
    END IF;

    -- The weaker form the migration replaced, kept as an explicit negative so a
    -- future amendment to the OLD expression is caught here and not only in the
    -- throwaway rehearsal.
    IF (SELECT pg_get_constraintdef(oid) FROM pg_constraint
         WHERE conrelid = 'public.ai_approvals'::regclass
           AND conname = 'ai_approvals_approval_check')
       NOT LIKE '%''REJECTED''%' THEN
        RAISE EXCEPTION
            'V14: ai_approvals_approval_check is back to a form that does not admit REJECTED as a '
            'decision-bearing state. That is the ADR-0011 section 1 defect.';
    END IF;

    -- Provenance: attribution is never optional, and the refusal/override pair
    -- can never both be claimed.
    IF EXISTS (SELECT 1 FROM public.ai_provenance_records
                WHERE "agentId" IS NULL OR "agentVersionId" IS NULL) THEN
        RAISE EXCEPTION 'V14: a provenance record with no agent or agent version -- unattributable';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_provenance_records
                WHERE "refused" AND "overriddenByUserId" IS NOT NULL) THEN
        RAISE EXCEPTION 'V14: a provenance record claims both a refusal and an override';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_provenance_records
                WHERE ("disposition" = 'OVERRIDDEN')
                      IS DISTINCT FROM ("overriddenByUserId" IS NOT NULL)) THEN
        RAISE EXCEPTION 'V14: a provenance disposition and its override column disagree';
    END IF;

    IF EXISTS (SELECT 1 FROM public.ai_provenance_records
                WHERE "toolDeniedCount" > "toolCallCount") THEN
        RAISE EXCEPTION 'V14: a provenance record denies more tool calls than it recorded';
    END IF;

    -- Redactions: which policy, which rule, which field, HOW MANY -- and never
    -- nothing, because "we redacted nothing" is not evidence.
    IF EXISTS (SELECT 1 FROM public.ai_redaction_records WHERE "valuesRedacted" < 1) THEN
        RAISE EXCEPTION
            'V14: a redaction record that removed nothing -- it makes a compliance report look '
            'better than reality';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 7/7a. THE BUDGET PERIOD RULE, which a CHECK cannot express: a consumption's
--    period bucket must be its budget's own period, or the budget under-reports.
--    MUST BE 0 rows. Plus the budget-versus-limit view the Alert screen shows.
--
--    Guarded, with dynamic SQL, for the same reason V13 sections 7-9 are: a
--    plain SELECT naming a dropped table is a PARSE-time error, and the bundle
--    must report PHASE710A_NOT_PRESENT after an undo rather than fail.
--    The MONTHLY bucket is tested with date_trunc rather than
--    `extract(last day FROM ...)`, because there is no `LAST DAY` field in
--    PostgreSQL's extract() and the first version failed with
--    `syntax error at or near "last"`. The month end is the day before the first
--    of the next month.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    rec record;
BEGIN
    IF to_regclass('public.ai_budgets') IS NULL THEN
        RAISE NOTICE 'V14 sections 7-7a: PHASE710A_NOT_PRESENT';
        RETURN;
    END IF;

    FOR rec IN EXECUTE $q$
        select c.id::text, c."periodStart", c."periodEnd", b."period"::text
          from public.ai_budget_consumptions c
          join public.ai_budgets b on b.id = c."budgetId"
         where (b."period" = 'DAILY'::"public"."AiBudgetPeriod"
                and c."periodEnd" <> c."periodStart")
            or (b."period" = 'MONTHLY'::"public"."AiBudgetPeriod"
                and (c."periodStart" <> date_trunc('month', c."periodStart")::date
                     or c."periodEnd"
                        <> (date_trunc('month', c."periodEnd" + interval '1 day')
                            - interval '1 day')::date))
    $q$
    LOOP
        RAISE NOTICE
            'V14 BUDGET PERIOD MISMATCH: consumption=% booked in %..% against a % budget -- the '
            'budget will not count it', rec.id, rec."periodStart", rec."periodEnd", rec."period";
    END LOOP;

    -- 7a. The ledger is a SUM over immutable rows, never a mutable counter.
    FOR rec IN EXECUTE $q$
        select b."key", b."period"::text, b."currency", b."limitAmount", b."enforcement",
               coalesce(sum(c."amount"), 0) as consumed,
               coalesce(sum(c."amount"), 0) > b."limitAmount" as over_limit
          from public.ai_budgets b
          left join public.ai_budget_consumptions c on c."budgetId" = b.id
         group by b.id, b."key", b."period", b."currency", b."limitAmount", b."enforcement"
         order by b."key"
    $q$
    LOOP
        RAISE NOTICE
            'V14 BUDGET %: period=% currency=% limit=% consumed=% over_limit=% enforcement=%',
            rec."key", rec."period", rec."currency", rec."limitAmount",
            rec."consumed", rec."over_limit", rec."enforcement";
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 8. Index justification. 8a is the tenant-less allow list: the AI slice adds
--     NONE, so this list must not gain an entry.
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

SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'public'
   AND tablename IN ('ai_policies', 'ai_policy_versions', 'ai_model_registry',
                     'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
                     'ai_approval_decisions', 'ai_redaction_records',
                     'ai_provenance_records', 'ai_tool_registry',
                     'ai_tool_executions')
 ORDER BY tablename, indexname;

-- 8b/8c. The two reads that matter: "is this tool enabled before I offer it" and
--     "which tools are being refused and why". EXPLAIN WITHOUT ANALYZE only.
DO $$
DECLARE
    plan       text;
    i          int;
    statements text[] := ARRAY[
        'explain (costs off) select "toolName" from public.ai_tool_registry
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "isEnabled"',
        'explain (costs off) select id from public.ai_tool_executions
          where "organizationId" = ''00000000-0000-0000-0000-000000000000''
            and "decision" = ''DENY''::"public"."AiPolicyDecision"
          order by "createdAt" desc limit 50'
    ];
BEGIN
    IF to_regclass('public.ai_tool_registry') IS NULL THEN
        RAISE NOTICE 'V14 8b-8c: PHASE710A_NOT_PRESENT (no read-model plan to prove)';
        RETURN;
    END IF;
    FOR i IN 1 .. 2 LOOP
        RAISE NOTICE 'V14 plan %:', i;
        FOR plan IN EXECUTE statements[i] LOOP
            RAISE NOTICE '   %', plan;
        END LOOP;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- 9. Session timezone.
-- ---------------------------------------------------------------------
SHOW TimeZone;