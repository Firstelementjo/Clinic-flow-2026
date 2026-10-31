-- =====================================================================
-- V20261005100000__ai_policy_and_governance.sql
-- ADDITIVE, IDEMPOTENT-BY-VERSION, NON-DESTRUCTIVE.
--
-- VERSION NUMBERING -- 14 digits (yyyyMMddHHmmss), numerically greater than
--   20261005090000.
--
-- PROMOTED FROM ops/flyway/pending/V202610040006__ai_policy_and_governance.sql.todo
--   (deleted on promotion).
--
-- WHY THIS MIGRATION EXISTS
--   ADR-0007 Part 2 and FR-5.3/5.5/5.6/5.9. The model may REQUEST a tool; the
--   APPLICATION decides whether it runs. These tables are the evidence that
--   policy -- not the model -- was in control, and they are the storage for that
--   evidence rather than for the model's reasoning.
--
-- WHAT IT CREATES
--   1. enum public.AiPolicyDecision (ALLOW, REQUIRE_APPROVAL, DENY)
--   2. enum public.AiApprovalStatus  (PENDING, APPROVED, REJECTED, EXPIRED, REVOKED)
--   3. enum public.AiRiskLevel       (LOW, MEDIUM, HIGH, CRITICAL)
--   4. enum public.AiBudgetPeriod    (DAILY, MONTHLY)
--   5. table public.ai_policies              -- mutable, the live policy
--   6. table public.ai_policy_versions       -- APPEND-ONLY
--   7. table public.ai_model_registry        -- provider/model registry, NO secrets
--   8. table public.ai_budgets               -- mutable
--   9. table public.ai_budget_consumptions   -- APPEND-ONLY
--  10. table public.ai_approvals             -- mutable state machine
--  11. table public.ai_approval_decisions    -- APPEND-ONLY
--  12. table public.ai_redaction_records     -- APPEND-ONLY
--  13. table public.ai_provenance_records    -- APPEND-ONLY
--  14. table public.ai_tool_registry         -- DENY BY DEFAULT
--  15. table public.ai_tool_executions       -- APPEND-ONLY
--  16. index ai_runs_id_organizationId_key    -- composite tenant key
--
-- THE RULE THAT MUST BE REPRESENTABLE, NOT JUST INTENDED
--   ADR-0007 Part 2: "Forbidden to the model: ... calling a disabled tool ...
--   denied and recorded, always." and "Deny by default: isEnabled defaults to
--   false so a newly registered tool is inert until an administrator enables it."
--   Three decisions make "a disabled tool is storable as disabled" TRUE:
--     * ai_tool_registry."isEnabled" is NOT NULL DEFAULT false. A newly
--       registered row is therefore inert by construction, and a migration that
--       changed the default would break every existing deployment's assumption
--       at once -- so the guard asserts the default is literally false.
--     * A DISABLED TOOL IS RECORDED AS DISABLED, with who and when:
--       ai_tool_registry_disabled_check is a BICONDITIONAL, so `isEnabled =
--       false` requires disabledAt, disabledByUserId and disabledReason. The
--       one-way version ("only a disabled tool may carry a disabledAt") is the
--       obvious half and was the first version of this constraint; the reverse
--       half is what makes "why is this tool off?" answerable.
--     * ai_tool_executions records the refusal: decision DENY requires
--       denyReason, and every execution -- allowed or refused -- is a row. A tool
--       the model tried to call while disabled is therefore not merely absent,
--       it is EVIDENCE.
--
-- AN APPROVAL RECORD MUST EXIST FOR ANY CONSEQUENTIAL ACTION
--   ADR-0007 Part 2's action classes: answerable (allowed), consequential
--   (human approval required; the model may only REQUEST), forbidden (denied).
--   ai_tool_executions."consequentialAction" is NOT NULL and:
--     * decision = REQUIRE_APPROVAL  ==> approvalId IS NOT NULL AND
--       executedAt IS NULL. The action was requested and not run.
--     * decision = ALLOW             ==> executedAt IS NOT NULL, and when the
--       action was consequential the approvalId is STILL required. This is the
--       biconditional the draft's design lacked: without it, a writer can mark a
--       consequential action ALLOW and execute it with no approval anywhere,
--       which is exactly the failure ADR-0007 Part 2 exists to make impossible.
--     * decision = DENY              ==> executedAt IS NULL AND denyReason IS NOT
--       NULL. A denied action never runs.
--
-- NO CHAIN-OF-THOUGHT, EVER (FR-5.4, ADR-0007 rule 4)
--   The baseline's ai_run_steps.detail is a summary payload and the target
--   contract must not widen it. ai_provenance_records therefore stores what
--   happened -- which agent version, which prompt version, which model, which
--   retrieved sources, how many tokens, how much cost, how many redactions, what
--   disposition -- and nothing else. The migration's guard FAILS if any column
--   whose name suggests reasoning (chainOfThought, thinking, reasoning,
--   scratchpad, internalMonologue) appears on any of these tables, and the
--   fixture asserts the same list.
--   ai_redaction_records stores WHICH POLICY, WHICH RULE, WHICH FIELD and HOW
--   MANY values were removed -- never the values themselves. The guard also fails
--   if a column named rawValue / originalText / originalContent appears.
--
-- NO SECRETS IN THE REGISTRY
--   ai_model_registry is a REGISTRY, not a credential store. There is no apiKey,
--   token, secret or password column, and the guard fails if one appears.
--   Credentials stay encrypted in channel_connections and are write-only through
--   the API (Phase 3 finding). A provider row that can hold a key is a row that
--   will eventually hold a key in a log.
--
-- THE BUDGET IS A CEILING, AND EXCEEDING IT IS A STATE NOT AN ERROR
--   ai_budgets carries the limit and period; ai_budget_consumptions is
--   append-only and additive, so "what have we spent this month" is a SUM over
--   immutable rows and never a mutable counter (plan.md rule 8's shape, applied to
--   budgets). The CHECK refuses a negative consumption and a zero limit, and
--   ai_budgets_consumption_pair_check keeps the ceiling and the observed total
--   consistent enough to alert on without the ledger being rewritten.
--
-- TENANT SCOPE
--   organizationId uuid NOT NULL on all eleven tables (FR-1.2). projectId:
--     * NOT NULL: ai_policies, ai_policy_versions, ai_budgets,
--       ai_budget_consumptions, ai_approvals, ai_approval_decisions,
--       ai_tool_executions. An AI policy that could not be attributed to a project
--       would be a project-scoped capability set with no scope, which is the
--       FR-1.3 hole in a different table.
--     * NULLABLE, organisation-wide: ai_model_registry, ai_tool_registry,
--       ai_redaction_records, ai_provenance_records. The provider registry, the
--       tool registry and the redaction/provenance records are tenant policy that
--       applies to every project; forcing per-project copies is how two projects
--       end up with different safety settings.
--   V14 section 4d lists them explicitly.
--
-- EXTENDS THE EXISTING ai_agents MODEL, DOES NOT DUPLICATE IT
--   The draft's open question 1 asked whether these tables extend ai_agents /
--   ai_agent_versions or sit beside them. They EXTEND: ai_provenance_records and
--   ai_tool_executions point at ai_agents / ai_agent_versions through COMPOSITE
--   tenant keys. Creating a second agent model would be plan.md rule 3's failure
--   mode in a new place.
--
-- TIMESTAMPS
--   timestamp(3) WITHOUT time zone; every default is
--   (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'); updatedAt is never defaulted.
--
-- SAFETY
--   CREATE TYPE / CREATE TABLE / CREATE INDEX IF NOT EXISTS only. No DROP, no
--   RENAME, no ALTER of an existing object, no data change. The single index on a
--   pre-existing table is ai_runs_id_organizationId_key, created IF NOT EXISTS
--   purely as a composite foreign-key target and removed by the undo.
--   Rollback: drop the eleven tables in reverse dependency order, the four types
--   and the index -- rehearsed on a throwaway database only
--   (ops/flyway/rollback/undo_phase7_10a.sql).
--   Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

CREATE TYPE "public"."AiPolicyDecision" AS ENUM (
    'ALLOW',
    'REQUIRE_APPROVAL',
    'DENY'
);

CREATE TYPE "public"."AiApprovalStatus" AS ENUM (
    'PENDING',
    'APPROVED',
    'REJECTED',
    'EXPIRED',
    'REVOKED'
);

CREATE TYPE "public"."AiRiskLevel" AS ENUM (
    'LOW',
    'MEDIUM',
    'HIGH',
    'CRITICAL'
);

CREATE TYPE "public"."AiBudgetPeriod" AS ENUM (
    'DAILY',
    'MONTHLY'
);

-- ---------------------------------------------------------------------
-- The composite tenant key on the existing agent model.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS "ai_runs_id_organizationId_key"
    ON "public"."ai_runs" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX IF NOT EXISTS "ai_agents_id_organizationId_key"
    ON "public"."ai_agents" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX IF NOT EXISTS "ai_agent_versions_id_organizationId_key"
    ON "public"."ai_agent_versions" USING "btree" ("id", "organizationId");

-- ---------------------------------------------------------------------
-- ai_tool_registry -- DENY BY DEFAULT, AND A DISABLED TOOL IS STORABLE
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_tool_registry" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "toolName"           text NOT NULL,
    "descriptionAr"      text,
    "schemaDefinition"   jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- NOT NULL. A tool with no required permission is an unrestricted capability,
    -- which is the thing plan.md forbids, so "not configured yet" is not an
    -- expressible state for it.
    "requiredPermission" text NOT NULL,
    -- DENY BY DEFAULT. A newly registered tool is inert until an administrator
    -- enables it, and the post-condition guard asserts this default is literally
    -- false so a future edit cannot quietly flip it.
    "isEnabled"          boolean NOT NULL DEFAULT false,
    -- A DISABLED TOOL SAYS WHO, WHEN AND WHY. Stated as a BICONDITIONAL so that
    -- "disabled without a record" and "enabled but still carrying a disabledAt"
    -- are both impossible.
    "disabledAt"         timestamp(3) without time zone,
    "disabledByUserId"   uuid,
    "disabledReason"     text,
    "enabledAt"          timestamp(3) without time zone,
    "enabledByUserId"    uuid,
    "fieldAllowList"     text[] NOT NULL DEFAULT '{}',
    "riskLevel"          "public"."AiRiskLevel" NOT NULL DEFAULT 'LOW',
    "consequential"      boolean NOT NULL DEFAULT false,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "ai_tool_registry_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_tool_registry_tool_name_check"
        CHECK (length(btrim("toolName")) > 0),
    CONSTRAINT "ai_tool_registry_required_permission_check"
        CHECK (length(btrim("requiredPermission")) > 0),
    -- THE DENY-BY-DEFAULT STATE MUST BE RECORDED. One-way ("only a disabled tool
    -- may carry a disabledAt") is the obvious half; the reverse half is what makes
    -- "why is this tool off?" answerable six months later.
    CONSTRAINT "ai_tool_registry_disabled_check"
        CHECK ((NOT "isEnabled")
               = ("disabledAt" IS NOT NULL AND "disabledByUserId" IS NOT NULL
                  AND "disabledReason" IS NOT NULL)),
    CONSTRAINT "ai_tool_registry_disabled_reason_check"
        CHECK ("disabledReason" IS NULL OR length(btrim("disabledReason")) > 0),
    -- Enabling is equally a decision somebody made.
    CONSTRAINT "ai_tool_registry_enabled_check"
        CHECK ("isEnabled" = ("enabledAt" IS NOT NULL AND "enabledByUserId" IS NOT NULL)),
    -- A HIGH or CRITICAL tool may not be enabled with an empty field allow-list.
    -- Without this, "enabled" plus no allow-list means "may read anything", which
    -- is the opposite of field-limited (ADR-0007 Part 2 rule 1).
    --
    -- THE coalesce IS LOAD-BEARING, and the fixture proved it.
    --   array_length('{}'::text[], 1) returns NULL, not 0. `NULL >= 1` is NULL,
    --   and a CHECK constraint passes when it evaluates to NULL rather than
    --   false. So the obvious spelling of this constraint -- exactly the spelling
    --   used here originally -- is satisfied by EVERY empty allow-list, and the
    --   capability it exists to refuse is expressible after all.
    --   coalesce(..., 0) >= 1 makes the empty array evaluate to false. Do not
    --   "simplify" this back to array_length(...) >= 1: it will pass the fixture
    --   only if the fixture's negative test is also removed, which is precisely
    --   the outcome to avoid.
    CONSTRAINT "ai_tool_registry_risk_field_list_check"
        CHECK (NOT "isEnabled"
               OR "riskLevel" NOT IN ('HIGH'::"public"."AiRiskLevel",
                                      'CRITICAL'::"public"."AiRiskLevel")
               OR coalesce(array_length("fieldAllowList", 1), 0) >= 1),
    -- A consequential tool that is enabled must have been enabled BY somebody, and
    -- that somebody is recorded: enabling a consequential capability is the
    -- decision ADR-0007 Part 2 says requires a human.
    CONSTRAINT "ai_tool_registry_consequential_enabled_check"
        CHECK (NOT ("consequential" AND "isEnabled") OR "enabledByUserId" IS NOT NULL),
    -- A blank permission string in the allow-list is a field nobody may read.
    CONSTRAINT "ai_tool_registry_field_allow_list_check"
        CHECK (NOT "public"."firstelement_array_has_blank"("fieldAllowList")),
    CONSTRAINT "ai_tool_registry_schema_shape_check"
        CHECK (jsonb_typeof("schemaDefinition") = 'object'),
    CONSTRAINT "ai_tool_registry_version_check"
        CHECK ("version" >= 0)
);

CREATE UNIQUE INDEX "ai_tool_registry_id_organizationId_key"
    ON "public"."ai_tool_registry" USING "btree" ("id", "organizationId");

-- TENANT-SCOPED tool name. A global unique index would be a cross-tenant existence
-- oracle for a capability name.
CREATE UNIQUE INDEX "ai_tool_registry_organizationId_toolName_key"
    ON "public"."ai_tool_registry" USING "btree"
       ("organizationId", "toolName");

-- The enabled-tool lookup the runtime does before offering a tool to the model.
CREATE INDEX "ai_tool_registry_organizationId_enabled_idx"
    ON "public"."ai_tool_registry" USING "btree"
       ("organizationId", "isEnabled");

ALTER TABLE ONLY "public"."ai_tool_registry"
    ADD CONSTRAINT "ai_tool_registry_disabled_by_tenant_fkey"
    FOREIGN KEY ("disabledByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_tool_registry"
    ADD CONSTRAINT "ai_tool_registry_enabled_by_tenant_fkey"
    FOREIGN KEY ("enabledByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- ai_policies -- the live policy, versioned like everything else
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_policies" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    "projectId"              uuid NOT NULL,
    "key"                    text NOT NULL,
    "nameAr"                 text NOT NULL,
    "nameEn"                 text NOT NULL,
    "isEnabled"              boolean NOT NULL DEFAULT false,
    "maxTokensPerRun"        integer,
    "maxToolCallsPerRun"     integer,
    "currentVersionId"       uuid,
    "budgetId"               uuid,
    -- The four classes ADR-0007 Part 2 names. All four default TRUE, and the
    -- defaults are the CONSERVATIVE choice: ADR-0007 rule 3 says "with no
    -- provider key the CRM stays fully usable" and rule 1 says the application
    -- owns execution, so the safe default is "ask a human", not "do it".
    "customerVisibleRequiresApproval"     boolean NOT NULL DEFAULT true,
    "financialActionRequiresApproval"     boolean NOT NULL DEFAULT true,
    "permissionChangeRequiresApproval"    boolean NOT NULL DEFAULT true,
    "externalSideEffectRequiresApproval"  boolean NOT NULL DEFAULT true,
    "redactionPolicy"         text NOT NULL,
    "retentionClass"          text NOT NULL,
    "failoverChain"           jsonb NOT NULL DEFAULT '[]'::jsonb,
    "version"                 integer NOT NULL DEFAULT 0,
    "createdAt"               timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"               timestamp(3) without time zone NOT NULL,
    CONSTRAINT "ai_policies_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_policies_key_check"
        CHECK (length(btrim("key")) > 0),
    CONSTRAINT "ai_policies_name_ar_check"
        CHECK (length(btrim("nameAr")) > 0),
    CONSTRAINT "ai_policies_name_en_check"
        CHECK (length(btrim("nameEn")) > 0),
    -- An UNDISABLED policy still has to promise its limits and its redaction
    -- policy; the ones that are optional are genuinely optional (no budget, no
    -- per-run token cap) but "no limits and no redaction" is not a configuration.
    CONSTRAINT "ai_policies_enabled_limits_check"
        CHECK ("isEnabled" = FALSE
               OR ("maxTokensPerRun" IS NOT NULL AND "redactionPolicy" IS NOT NULL)),
    CONSTRAINT "ai_policies_max_tokens_check"
        CHECK ("maxTokensPerRun" IS NULL OR "maxTokensPerRun" > 0),
    CONSTRAINT "ai_policies_max_tool_calls_check"
        CHECK ("maxToolCallsPerRun" IS NULL OR "maxToolCallsPerRun" >= 0),
    CONSTRAINT "ai_policies_redaction_policy_check"
        CHECK (length(btrim("redactionPolicy")) > 0),
    CONSTRAINT "ai_policies_retention_class_check"
        CHECK (length(btrim("retentionClass")) > 0),
    -- ADR-0007 rule 3: a provider outage must not break the CRM, so the failover
    -- chain is ORDERED and its entries are named model ids from the registry.
    CONSTRAINT "ai_policies_failover_chain_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("failoverChain")),
    CONSTRAINT "ai_policies_version_check"
        CHECK ("version" >= 0)
);

CREATE UNIQUE INDEX "ai_policies_id_organizationId_key"
    ON "public"."ai_policies" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "ai_policies_organizationId_projectId_key_key"
    ON "public"."ai_policies" USING "btree"
       ("organizationId", "projectId", "key");

CREATE INDEX "ai_policies_organizationId_projectId_enabled_idx"
    ON "public"."ai_policies" USING "btree"
       ("organizationId", "projectId", "isEnabled");

-- ---------------------------------------------------------------------
-- ai_policy_versions -- APPEND-ONLY
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_policy_versions" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    "projectId"              uuid NOT NULL,
    "aiPolicyId"             uuid NOT NULL,
    "versionNumber"          integer NOT NULL,
    "maxTokensPerRun"        integer,
    "maxToolCallsPerRun"     integer,
    "requireApprovalClasses" text[] NOT NULL DEFAULT '{}',
    "redactionPolicy"        text NOT NULL,
    "retentionClass"         text NOT NULL,
    "failoverChain"          jsonb NOT NULL DEFAULT '[]'::jsonb,
    "changeNote"             text NOT NULL,
    "state"                  "public"."AiApprovalStatus" NOT NULL DEFAULT 'PENDING',
    "createdByUserId"        uuid NOT NULL,
    "approvedAt"             timestamp(3) without time zone,
    "approvedByUserId"       uuid,
    "createdAt"              timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "ai_policy_versions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_policy_versions_version_number_check"
        CHECK ("versionNumber" >= 1),
    CONSTRAINT "ai_policy_versions_change_note_check"
        CHECK (length(btrim("changeNote")) > 0),
    CONSTRAINT "ai_policy_versions_redaction_policy_check"
        CHECK (length(btrim("redactionPolicy")) > 0),
    CONSTRAINT "ai_policy_versions_retention_class_check"
        CHECK (length(btrim("retentionClass")) > 0),
    -- The classes are NAMED, not free text: an approval class nobody spelled is an
    -- approval class the policy engine cannot branch on.
    CONSTRAINT "ai_policy_versions_require_approval_classes_check"
        CHECK (NOT "public"."firstelement_array_has_blank"("requireApprovalClasses")),
    CONSTRAINT "ai_policy_versions_failover_chain_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("failoverChain")),
    -- An APPROVED version says who and when; PENDING/REJECTED/EXPIRED/REVOKED do
    -- not. A policy that was "approved" with nobody named is not approved.
    CONSTRAINT "ai_policy_versions_approval_check"
        CHECK (("state" = 'APPROVED'::"public"."AiApprovalStatus")
               = ("approvedAt" IS NOT NULL AND "approvedByUserId" IS NOT NULL)),
    CONSTRAINT "ai_policy_versions_max_tokens_check"
        CHECK ("maxTokensPerRun" IS NULL OR "maxTokensPerRun" > 0)
);

CREATE UNIQUE INDEX "ai_policy_versions_id_organizationId_key"
    ON "public"."ai_policy_versions" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "ai_policy_versions_policy_version_key"
    ON "public"."ai_policy_versions" USING "btree"
       ("organizationId", "aiPolicyId", "versionNumber");

-- AT MOST ONE APPROVED VERSION PER POLICY. A partial unique index, and DRAFT-like
-- states coexist freely -- the same reason Phase 6a added DRAFT to SlaCalendarState
-- after the throwaway rehearsal found the authoring deadlock.
CREATE UNIQUE INDEX "ai_policy_versions_one_approved_per_policy"
    ON "public"."ai_policy_versions" USING "btree"
       ("organizationId", "aiPolicyId")
    WHERE "state" = 'APPROVED';

CREATE INDEX "ai_policy_versions_organizationId_policy_idx"
    ON "public"."ai_policy_versions" USING "btree"
       ("organizationId", "aiPolicyId", "versionNumber");

ALTER TABLE ONLY "public"."ai_policy_versions"
    ADD CONSTRAINT "ai_policy_versions_policy_tenant_fkey"
    FOREIGN KEY ("aiPolicyId", "organizationId")
    REFERENCES "public"."ai_policies"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."ai_policy_versions"
    ADD CONSTRAINT "ai_policy_versions_author_tenant_fkey"
    FOREIGN KEY ("createdByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_policy_versions"
    ADD CONSTRAINT "ai_policy_versions_approver_tenant_fkey"
    FOREIGN KEY ("approvedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- ai_model_registry -- a REGISTRY, never a credential store
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_model_registry" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    "projectId"              uuid,
    -- Reuses the BASELINE's AIProviderKind enum rather than inventing a second
    -- provider taxonomy. One vocabulary for "which provider" is worth more than
    -- a table-local one, and the baseline enum is already in the verified schema.
    "provider"               "public"."AIProviderKind" NOT NULL,
    "model"                  text NOT NULL,
    "displayNameAr"          text,
    "displayNameEn"          text,
    "isEnabled"              boolean NOT NULL DEFAULT false,
    "contextWindowTokens"    integer,
    "maxOutputTokens"        integer,
    "inputCostPer1kTokens"   numeric(20,8),
    "outputCostPer1kTokens"  numeric(20,8),
    "costCurrency"           text,
    -- EXPLICIT, no default. A provider whose data-retention policy is unknown is
    -- not one a clinic can lawfully switch on, so "unknown" is not expressible.
    "dataRetentionPolicy"    text NOT NULL,
    "sendsCustomerData"      boolean NOT NULL DEFAULT false,
    "disabledAt"             timestamp(3) without time zone,
    "disabledReason"         text,
    "version"                integer NOT NULL DEFAULT 0,
    "createdAt"              timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"              timestamp(3) without time zone NOT NULL,
    CONSTRAINT "ai_model_registry_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_model_registry_model_check"
        CHECK (length(btrim("model")) > 0),
    CONSTRAINT "ai_model_registry_retention_policy_check"
        CHECK (length(btrim("dataRetentionPolicy")) > 0),
    -- A provider that receives customer data must be ENABLED EXPLICITLY and must
    -- SAY so, because this is the switch a data-protection review looks for.
    CONSTRAINT "ai_model_registry_customer_data_check"
        CHECK (NOT ("sendsCustomerData" AND "isEnabled")
               OR length(btrim("dataRetentionPolicy")) > 0),
    CONSTRAINT "ai_model_registry_context_window_check"
        CHECK ("contextWindowTokens" IS NULL OR "contextWindowTokens" > 0),
    CONSTRAINT "ai_model_registry_max_output_check"
        CHECK ("maxOutputTokens" IS NULL OR "maxOutputTokens" > 0),
    CONSTRAINT "ai_model_registry_input_cost_check"
        CHECK ("inputCostPer1kTokens" IS NULL OR "inputCostPer1kTokens" >= 0),
    CONSTRAINT "ai_model_registry_output_cost_check"
        CHECK ("outputCostPer1kTokens" IS NULL OR "outputCostPer1kTokens" >= 0),
    -- Cost figures without a currency are not costs.
    CONSTRAINT "ai_model_registry_cost_currency_check"
        CHECK (("inputCostPer1kTokens" IS NULL AND "outputCostPer1kTokens" IS NULL)
               = ("costCurrency" IS NULL)),
    CONSTRAINT "ai_model_registry_cost_currency_shape_check"
        CHECK ("costCurrency" IS NULL
               OR "public"."firstelement_currency_code"("costCurrency")),
    CONSTRAINT "ai_model_registry_disabled_check"
        CHECK ((NOT "isEnabled") = ("disabledAt" IS NOT NULL)),
    CONSTRAINT "ai_model_registry_disabled_reason_check"
        CHECK ("isEnabled" OR ("disabledReason" IS NOT NULL
                               AND length(btrim("disabledReason")) > 0)),
    CONSTRAINT "ai_model_registry_version_check"
        CHECK ("version" >= 0)
);

CREATE UNIQUE INDEX "ai_model_registry_id_organizationId_key"
    ON "public"."ai_model_registry" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "ai_model_registry_organizationId_provider_model_key"
    ON "public"."ai_model_registry" USING "btree"
       ("organizationId", "provider", "model");

CREATE INDEX "ai_model_registry_organizationId_enabled_idx"
    ON "public"."ai_model_registry" USING "btree"
       ("organizationId", "isEnabled");

-- ---------------------------------------------------------------------
-- ai_budgets
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_budgets" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "key"                text NOT NULL,
    "nameAr"             text NOT NULL,
    "nameEn"             text NOT NULL,
    "period"             "public"."AiBudgetPeriod" NOT NULL,
    "currency"           text NOT NULL,
    "limitAmount"        numeric(20,6) NOT NULL,
    -- Whether crossing the limit stops the run (HARD) or only raises an alert
    -- (SOFT). ADR-0007 leaves the enforcement point open, so it is stored as
    -- DATA rather than being guessed in code, and an unset enforcement point is
    -- not expressible.
    "enforcement"        text NOT NULL DEFAULT 'ALERT',
    "isEnabled"          boolean NOT NULL DEFAULT false,
    "validFrom"          date NOT NULL,
    "validTo"            date,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "ai_budgets_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_budgets_key_check"
        CHECK (length(btrim("key")) > 0),
    CONSTRAINT "ai_budgets_name_ar_check"
        CHECK (length(btrim("nameAr")) > 0),
    CONSTRAINT "ai_budgets_name_en_check"
        CHECK (length(btrim("nameEn")) > 0),
    -- A budget of zero stops everything; a negative budget is a sign error.
    CONSTRAINT "ai_budgets_limit_check"
        CHECK ("limitAmount" > 0),
    CONSTRAINT "ai_budgets_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    -- ADR-0007 leaves the enforcement point open; ALERT is the default because it
    -- is the only value that cannot surprise an operator by stopping work.
    CONSTRAINT "ai_budgets_enforcement_check"
        CHECK ("enforcement" IN ('ALERT', 'HARD', 'OFF')),
    CONSTRAINT "ai_budgets_enforcement_off_check"
        CHECK ("enforcement" <> 'OFF' OR NOT "isEnabled"),
    CONSTRAINT "ai_budgets_validity_check"
        CHECK ("validTo" IS NULL OR "validTo" >= "validFrom"),
    CONSTRAINT "ai_budgets_version_check"
        CHECK ("version" >= 0)
);

CREATE UNIQUE INDEX "ai_budgets_id_organizationId_key"
    ON "public"."ai_budgets" USING "btree" ("id", "organizationId");

CREATE UNIQUE INDEX "ai_budgets_organizationId_projectId_key_period_key"
    ON "public"."ai_budgets" USING "btree"
       ("organizationId", "projectId", "key", "period");

CREATE INDEX "ai_budgets_organizationId_enabled_idx"
    ON "public"."ai_budgets" USING "btree" ("organizationId", "isEnabled");

-- ---------------------------------------------------------------------
-- ai_budget_consumptions -- APPEND-ONLY
-- ---------------------------------------------------------------------
-- A budget is a SUM over immutable rows, never a mutable counter (plan.md rule 8's
-- shape applied to budgets): "what have we spent this month" must be derivable
-- from the same ledger the finance read model uses.
CREATE TABLE "public"."ai_budget_consumptions" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "budgetId"           uuid NOT NULL,
    "aiRunId"            uuid,
    "periodStart"        date NOT NULL,
    "periodEnd"          date NOT NULL,
    "currency"           text NOT NULL,
    "amount"             numeric(20,6) NOT NULL,
    "inputTokens"        integer,
    "outputTokens"       integer,
    "recordedAt"         timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "ai_budget_consumptions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_budget_consumptions_amount_check"
        CHECK ("amount" >= 0),
    CONSTRAINT "ai_budget_consumptions_currency_check"
        CHECK ("public"."firstelement_currency_code"("currency")),
    CONSTRAINT "ai_budget_consumptions_period_check"
        CHECK ("periodEnd" >= "periodStart"),
    -- THE PERIOD BUCKET IS WRITTEN EXPLICITLY, not inherited. A CHECK cannot read
    -- the budget row to confirm that periodStart/periodEnd are the budget's own
    -- period, so the rule is asserted WITH DATA by V14 section 6 instead of being
    -- pretended here. What IS enforced here is the half that needs no other row:
    -- a consumption cannot be booked without naming its bucket, because an
    -- unbucketed consumption is invisible to the budget that is supposed to count
    -- it. NOTE: no CHECK is written on periodStart/periodEnd being NOT NULL -- they
    -- already are, and a tautology dressed as an invariant teaches reviewers that
    -- CHECK constraints are decoration.
    CONSTRAINT "ai_budget_consumptions_tokens_check"
        CHECK (("inputTokens" IS NULL OR "inputTokens" >= 0)
               AND ("outputTokens" IS NULL OR "outputTokens" >= 0))
);

ALTER TABLE ONLY "public"."ai_budget_consumptions"
    ADD CONSTRAINT "ai_budget_consumptions_budget_tenant_fkey"
    FOREIGN KEY ("budgetId", "organizationId")
    REFERENCES "public"."ai_budgets"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."ai_budget_consumptions"
    ADD CONSTRAINT "ai_budget_consumptions_run_tenant_fkey"
    FOREIGN KEY ("aiRunId", "organizationId")
    REFERENCES "public"."ai_runs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE UNIQUE INDEX "ai_budget_consumptions_id_organizationId_key"
    ON "public"."ai_budget_consumptions" USING "btree" ("id", "organizationId");

-- Duplicate suppression: one consumption per (budget, run, period). A retried
-- accounting write must not double-charge a budget.
CREATE UNIQUE INDEX "ai_budget_consumptions_budget_run_period_key"
    ON "public"."ai_budget_consumptions" USING "btree"
       ("organizationId", "budgetId", "periodStart", "aiRunId");

CREATE INDEX "ai_budget_consumptions_organizationId_budget_period_idx"
    ON "public"."ai_budget_consumptions" USING "btree"
       ("organizationId", "budgetId", "periodStart");

-- ---------------------------------------------------------------------
-- ai_approvals -- a human decision record
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_approvals" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "aiRunId"            uuid NOT NULL,
    "action"             text NOT NULL,
    "actionClass"        text NOT NULL,
    "status"             "public"."AiApprovalStatus" NOT NULL DEFAULT 'PENDING',
    "requestedByUserId"  uuid,
    "requestedByPrincipal" text NOT NULL,
    "reason"             text,
    "requestedAt"        timestamp(3) without time zone NOT NULL,
    "decidedByUserId"    uuid,
    "decidedAt"          timestamp(3) without time zone,
    "decisionNote"       text,
    "revokedByUserId"    uuid,
    "revokedAt"          timestamp(3) without time zone,
    "revokeReason"       text,
    "expiresAt"          timestamp(3) without time zone NOT NULL,
    -- NO updatedAt in the draft; ADDED HERE DELIBERATELY. An approval is a state
    -- machine a human moves through, so it IS mutable -- but the transitions are
    -- also recorded append-only in ai_approval_decisions, so the mutable row is
    -- the current state and the history is the evidence.
    "updatedAt"          timestamp(3) without time zone NOT NULL,
    CONSTRAINT "ai_approvals_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_approvals_action_check"
        CHECK (length(btrim("action")) > 0),
    -- THE THREE ACTION CLASSES OF ADR-0007 PART 2, spelled exactly. A class
    -- nobody spelled is a class the policy engine cannot branch on.
    CONSTRAINT "ai_approvals_action_class_check"
        CHECK ("actionClass" IN ('ANSWERABLE', 'CONSEQUENTIAL', 'FORBIDDEN')),
    -- ...but only CONSEQUENTIAL IS EVER APPROVABLE, and that is the RULE rather than
    -- the taxonomy. An approval request for a FORBIDDEN action is a policy bug --
    -- the model may only REQUEST a consequential action, and a forbidden one is
    -- denied immediately -- and an approval request for an ANSWERABLE action is
    -- noise, because approving it teaches operators that the queue is decorative.
    -- The first draft expressed this as two separate CHECKs, one for FORBIDDEN and
    -- one for ANSWERABLE, which is two ways of saying the same thing; the rehearsal
    -- review collapsed them into the single rule they were both reaching for.
    -- ANSWERABLE and FORBIDDEN actions are recorded in ai_tool_executions instead,
    -- with decision ALLOW and decision DENY respectively.
    CONSTRAINT "ai_approvals_only_consequential_is_approvable_check"
        CHECK ("actionClass" = 'CONSEQUENTIAL'),
    CONSTRAINT "ai_approvals_requester_check"
        CHECK ("requestedByUserId" IS NOT NULL
               OR length(btrim("requestedByPrincipal")) > 0),
    CONSTRAINT "ai_approvals_requester_principal_check"
        CHECK (length(btrim("requestedByPrincipal")) > 0),
    -- The decision pair, and it is a BICONDITIONAL: APPROVED and REJECTED each say
    -- who and when, and nothing else carries a decision.
    CONSTRAINT "ai_approvals_decision_pair_check"
        CHECK (("decidedAt" IS NULL) = ("decidedByUserId" IS NULL)),
    CONSTRAINT "ai_approvals_approval_check"
        CHECK (("status" = 'APPROVED'::"public"."AiApprovalStatus")
               = ("decidedAt" IS NOT NULL)),
    -- A rejection without a reason is a decision nobody can learn from.
    CONSTRAINT "ai_approvals_rejection_check"
        CHECK ("status" <> 'REJECTED'::"public"."AiApprovalStatus"
               OR ("decisionNote" IS NOT NULL AND length(btrim("decisionNote")) > 0)),
    CONSTRAINT "ai_approvals_revocation_check"
        CHECK (("status" = 'REVOKED'::"public"."AiApprovalStatus")
               = ("revokedAt" IS NOT NULL AND "revokedByUserId" IS NOT NULL
                  AND "revokeReason" IS NOT NULL)),
    -- An approval must expire BEFORE it could be used forever: an open-ended
    -- approval is a permanent delegation.
    CONSTRAINT "ai_approvals_expiry_check"
        CHECK ("expiresAt" > "requestedAt"),
    -- Nobody approves their own request. plan.md forbids an unrestricted
    -- identity; self-approval is the same problem wearing a workflow.
    CONSTRAINT "ai_approvals_not_self_approved_check"
        CHECK ("decidedByUserId" IS NULL OR "decidedByUserId" <> "requestedByUserId")
);

CREATE UNIQUE INDEX "ai_approvals_id_organizationId_key"
    ON "public"."ai_approvals" USING "btree" ("id", "organizationId");

-- ONE PENDING REQUEST PER (RUN, ACTION). Two humans approving "book appointment"
-- for one run because the UI double-posted is exactly the double-decision this
-- index prevents, and it is scoped to PENDING so a re-request after a rejection is
-- legitimate.
CREATE UNIQUE INDEX "ai_approvals_one_pending_per_run_action"
    ON "public"."ai_approvals" USING "btree"
       ("organizationId", "aiRunId", "action")
    WHERE "status" = 'PENDING';

CREATE INDEX "ai_approvals_organizationId_projectId_status_idx"
    ON "public"."ai_approvals" USING "btree"
       ("organizationId", "projectId", "status", "requestedAt");

CREATE INDEX "ai_approvals_organizationId_run_idx"
    ON "public"."ai_approvals" USING "btree" ("organizationId", "aiRunId");

ALTER TABLE ONLY "public"."ai_approvals"
    ADD CONSTRAINT "ai_approvals_run_tenant_fkey"
    FOREIGN KEY ("aiRunId", "organizationId")
    REFERENCES "public"."ai_runs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_approvals"
    ADD CONSTRAINT "ai_approvals_requester_tenant_fkey"
    FOREIGN KEY ("requestedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_approvals"
    ADD CONSTRAINT "ai_approvals_decider_tenant_fkey"
    FOREIGN KEY ("decidedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_approvals"
    ADD CONSTRAINT "ai_approvals_revoker_tenant_fkey"
    FOREIGN KEY ("revokedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- ai_approval_decisions -- APPEND-ONLY
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_approval_decisions" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid NOT NULL,
    "approvalId"         uuid NOT NULL,
    "fromStatus"         "public"."AiApprovalStatus",
    "toStatus"           "public"."AiApprovalStatus" NOT NULL,
    "actorUserId"        uuid,
    "actorPrincipal"     text NOT NULL,
    "note"               text,
    "decidedAt"          timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "ai_approval_decisions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_approval_decisions_actor_check"
        CHECK ("actorUserId" IS NOT NULL OR length(btrim("actorPrincipal")) > 0),
    CONSTRAINT "ai_approval_decisions_actor_principal_check"
        CHECK (length(btrim("actorPrincipal")) > 0),
    -- The first decision of a request has no prior status; every later one does.
    CONSTRAINT "ai_approval_decisions_from_status_check"
        CHECK ("fromStatus" IS NOT NULL OR "toStatus" = 'PENDING'::"public"."AiApprovalStatus"),
    -- A terminal decision that says nothing is not a record.
    CONSTRAINT "ai_approval_decisions_terminal_note_check"
        CHECK ("toStatus" = 'PENDING'::"public"."AiApprovalStatus"
               OR ("note" IS NOT NULL AND length(btrim("note")) > 0)),
    -- A decision cannot be a no-op: from and to must differ.
    CONSTRAINT "ai_approval_decisions_no_op_check"
        CHECK ("fromStatus" IS NULL OR "fromStatus" <> "toStatus")
);

ALTER TABLE ONLY "public"."ai_approval_decisions"
    ADD CONSTRAINT "ai_approval_decisions_approval_tenant_fkey"
    FOREIGN KEY ("approvalId", "organizationId")
    REFERENCES "public"."ai_approvals"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."ai_approval_decisions"
    ADD CONSTRAINT "ai_approval_decisions_actor_tenant_fkey"
    FOREIGN KEY ("actorUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX "ai_approval_decisions_organizationId_approval_idx"
    ON "public"."ai_approval_decisions" USING "btree"
       ("organizationId", "approvalId", "decidedAt");

-- ---------------------------------------------------------------------
-- ai_redaction_records -- APPEND-ONLY, POLICY AND COUNTS ONLY
-- ---------------------------------------------------------------------
-- WHICH policy, WHICH rule, WHICH field, HOW MANY values. Never the values. The
-- post-condition guard fails if a column named rawValue / originalText /
-- originalContent appears, because a redaction record that stores what it removed
-- is a copy of the thing that was supposed to be removed.
CREATE TABLE "public"."ai_redaction_records" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "aiRunId"            uuid,
    "policyId"           uuid,
    "policyVersionId"    uuid,
    "redactionPolicy"    text NOT NULL,
    "ruleId"             text NOT NULL,
    "fieldPath"          text NOT NULL,
    "valuesRedacted"     integer NOT NULL,
    "charactersRedacted" integer,
    "redactedAt"         timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"          timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "ai_redaction_records_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_redaction_records_policy_check"
        CHECK (length(btrim("redactionPolicy")) > 0),
    CONSTRAINT "ai_redaction_records_rule_check"
        CHECK (length(btrim("ruleId")) > 0),
    CONSTRAINT "ai_redaction_records_field_path_check"
        CHECK (length(btrim("fieldPath")) > 0),
    -- A redaction record that removed nothing is not evidence of redaction, and it
    -- is exactly the row that makes a compliance report look better than reality.
    CONSTRAINT "ai_redaction_records_count_check"
        CHECK ("valuesRedacted" >= 1),
    CONSTRAINT "ai_redaction_records_characters_check"
        CHECK ("charactersRedacted" IS NULL OR "charactersRedacted" >= 0)
);

ALTER TABLE ONLY "public"."ai_redaction_records"
    ADD CONSTRAINT "ai_redaction_records_run_tenant_fkey"
    FOREIGN KEY ("aiRunId", "organizationId")
    REFERENCES "public"."ai_runs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_redaction_records"
    ADD CONSTRAINT "ai_redaction_records_policy_tenant_fkey"
    FOREIGN KEY ("policyId", "organizationId")
    REFERENCES "public"."ai_policies"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE UNIQUE INDEX "ai_redaction_records_id_organizationId_key"
    ON "public"."ai_redaction_records" USING "btree" ("id", "organizationId");

CREATE INDEX "ai_redaction_records_organizationId_run_idx"
    ON "public"."ai_redaction_records" USING "btree"
       ("organizationId", "aiRunId");

-- The compliance report read: which rules fired, how often, for this tenant.
CREATE INDEX "ai_redaction_records_organizationId_rule_idx"
    ON "public"."ai_redaction_records" USING "btree"
       ("organizationId", "ruleId", "redactedAt");

-- ---------------------------------------------------------------------
-- ai_provenance_records -- APPEND-ONLY, ATTRIBUTABILITY (ADR-0007 rule 7)
-- ---------------------------------------------------------------------
-- "Every AI action is attributable to agent version, prompt version, model and
-- provider, retrieved sources, tool calls, token use, cost, redactions,
-- approvals, refusal, override, and final disposition."
-- One row per AI run per project, holding exactly those facts and no reasoning.
CREATE TABLE "public"."ai_provenance_records" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    "projectId"              uuid,
    "aiRunId"                uuid NOT NULL,
    "agentId"                uuid,
    "agentVersionId"         uuid,
    "promptVersion"          text,
    "modelRegistryId"        uuid,
    "provider"               "public"."AIProviderKind",
    "model"                  text,
    "policyId"               uuid,
    "policyVersionId"        uuid,
    "retrievalIds"           text[] NOT NULL DEFAULT '{}',
    "toolCallCount"          integer NOT NULL DEFAULT 0,
    "toolDeniedCount"        integer NOT NULL DEFAULT 0,
    "approvalId"             uuid,
    "inputTokens"            integer,
    "outputTokens"           integer,
    "costAmount"             numeric(20,6),
    "costCurrency"           text,
    "redactionCount"         integer NOT NULL DEFAULT 0,
    "refused"                boolean NOT NULL DEFAULT false,
    "overriddenByUserId"     uuid,
    "disposition"            text NOT NULL,
    "decidedAt"              timestamp(3) without time zone NOT NULL,
    -- NO updatedAt.
    "createdAt"              timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "ai_provenance_records_pkey" PRIMARY KEY ("id"),
    -- A disposition is a CLOSED list. "the model did something" is not an
    -- attribution, and an open-ended disposition field is where attribution
    -- quietly stops.
    CONSTRAINT "ai_provenance_records_disposition_check"
        CHECK ("disposition" IN ('ANSWERED', 'ASKED_CLARIFICATION', 'ESCALATED',
                                 'NO_ACTION', 'RESOLVED', 'REFUSED', 'OVERRIDDEN')),
    -- A refusal and an override are mutually exclusive claims about the same run.
    CONSTRAINT "ai_provenance_records_refusal_override_check"
        CHECK (NOT ("refused" AND "overriddenByUserId" IS NOT NULL)),
    CONSTRAINT "ai_provenance_records_override_check"
        CHECK (("disposition" = 'OVERRIDDEN')
               = ("overriddenByUserId" IS NOT NULL)),
    CONSTRAINT "ai_provenance_records_refusal_check"
        CHECK (NOT "refused" OR "disposition" = 'REFUSED'),
    CONSTRAINT "ai_provenance_records_tool_counts_check"
        CHECK ("toolCallCount" >= 0 AND "toolDeniedCount" >= 0),
    -- Denials cannot exceed calls: that inversion means the counter was written
    -- by two code paths and neither is trustworthy.
    CONSTRAINT "ai_provenance_records_denied_within_calls_check"
        CHECK ("toolDeniedCount" <= "toolCallCount"),
    CONSTRAINT "ai_provenance_records_tokens_check"
        CHECK (("inputTokens" IS NULL OR "inputTokens" >= 0)
               AND ("outputTokens" IS NULL OR "outputTokens" >= 0)),
    CONSTRAINT "ai_provenance_records_cost_check"
        CHECK ("costAmount" IS NULL OR "costAmount" >= 0),
    CONSTRAINT "ai_provenance_records_cost_currency_pair_check"
        CHECK (("costAmount" IS NULL) = ("costCurrency" IS NULL)),
    CONSTRAINT "ai_provenance_records_cost_currency_shape_check"
        CHECK ("costCurrency" IS NULL
               OR "public"."firstelement_currency_code"("costCurrency")),
    CONSTRAINT "ai_provenance_records_redaction_count_check"
        CHECK ("redactionCount" >= 0),
    -- Attribution is not optional: the agent and its version are who did this.
    -- Prompt version is nullable because a prompt may be inline, and the
    -- inline/managed distinction is a Phase 10 decision -- but the AGENT is never
    -- nullable.
    CONSTRAINT "ai_provenance_records_attribution_check"
        CHECK ("agentId" IS NOT NULL AND "agentVersionId" IS NOT NULL),
    CONSTRAINT "ai_provenance_records_retrieval_ids_check"
        CHECK (NOT "public"."firstelement_array_has_blank"("retrievalIds")),
    -- A denied tool call means there was something to deny.
    CONSTRAINT "ai_provenance_records_denied_calls_check"
        CHECK ("toolDeniedCount" = 0 OR "toolCallCount" > 0)
);

-- Composite tenant key, declared before the FKs.
CREATE UNIQUE INDEX "ai_provenance_records_id_organizationId_key"
    ON "public"."ai_provenance_records" USING "btree" ("id", "organizationId");

-- ONE PROVENANCE ROW PER RUN. A run with two provenance rows has two accounts of
-- what it did, and the operator cannot tell which one the customer saw.
CREATE UNIQUE INDEX "ai_provenance_records_run_key"
    ON "public"."ai_provenance_records" USING "btree"
       ("organizationId", "aiRunId");

CREATE INDEX "ai_provenance_records_organizationId_decidedAt_idx"
    ON "public"."ai_provenance_records" USING "btree"
       ("organizationId", "decidedAt");

ALTER TABLE ONLY "public"."ai_provenance_records"
    ADD CONSTRAINT "ai_provenance_records_run_tenant_fkey"
    FOREIGN KEY ("aiRunId", "organizationId")
    REFERENCES "public"."ai_runs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."ai_provenance_records"
    ADD CONSTRAINT "ai_provenance_records_agent_tenant_fkey"
    FOREIGN KEY ("agentId", "organizationId")
    REFERENCES "public"."ai_agents"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_provenance_records"
    ADD CONSTRAINT "ai_provenance_records_agent_version_tenant_fkey"
    FOREIGN KEY ("agentVersionId", "organizationId")
    REFERENCES "public"."ai_agent_versions"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_provenance_records"
    ADD CONSTRAINT "ai_provenance_records_approval_tenant_fkey"
    FOREIGN KEY ("approvalId", "organizationId")
    REFERENCES "public"."ai_approvals"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- ai_tool_executions -- APPEND-ONLY, AND THE APPROVAL BICONDITIONAL
-- ---------------------------------------------------------------------
CREATE TABLE "public"."ai_tool_executions" (
    "id"                     uuid NOT NULL,
    "organizationId"         uuid NOT NULL,
    "projectId"              uuid NOT NULL,
    "aiRunId"                uuid,
    "toolName"               text NOT NULL,
    -- The ACTION CLASS of ADR-0007 Part 2. NOT NULL because the whole table turns
    -- on it: an execution of a CONSEQUENTIAL action with no approval is the
    -- failure this schema exists to make unrepresentable.
    "actionClass"            text NOT NULL,
    "consequentialAction"    boolean NOT NULL,
    "decision"               "public"."AiPolicyDecision" NOT NULL,
    "denyReason"             text,
    "approvalId"             uuid,
    -- REDACTED FORM ONLY (FR-10.6). The guard fails if a column named
    -- argumentsRaw / resultRaw / rawArguments appears.
    "argumentsRedacted"      jsonb NOT NULL DEFAULT '{}'::jsonb,
    "resultRedacted"         jsonb NOT NULL DEFAULT '{}'::jsonb,
    "principalType"          text NOT NULL,
    "actorUserId"            uuid,
    "agentVersionId"         uuid,
    "latencyMs"              integer,
    "requestedAt"            timestamp(3) without time zone NOT NULL,
    "executedAt"             timestamp(3) without time zone,
    -- NO updatedAt.
    "createdAt"              timestamp(3) without time zone DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') NOT NULL,
    CONSTRAINT "ai_tool_executions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ai_tool_executions_tool_name_check"
        CHECK (length(btrim("toolName")) > 0),
    CONSTRAINT "ai_tool_executions_action_class_check"
        CHECK ("actionClass" IN ('ANSWERABLE', 'CONSEQUENTIAL', 'FORBIDDEN')),
    -- `consequentialAction` is DERIVED FROM `actionClass` and is stated as a
    -- biconditional, so the two can never disagree. Two copies of one fact is
    -- exactly how a consequential action gets executed without an approval
    -- because somebody read the other column.
    CONSTRAINT "ai_tool_executions_class_consistency_check"
        CHECK ("consequentialAction" = ("actionClass" = 'CONSEQUENTIAL')),
    -- THE APPROVAL BICONDITIONAL -- the load-bearing rule.
    --   REQUIRE_APPROVAL : asked for, NOT run, and names the approval it waits on.
    --   ALLOW            : run, and if the action was consequential it STILL names
    --                      an approval. Without this half, a writer can mark a
    --                      consequential action ALLOW, execute it, and record no
    --                      approval anywhere.
    --   DENY             : not run, and says why.
    CONSTRAINT "ai_tool_executions_require_approval_check"
        CHECK ("decision" <> 'REQUIRE_APPROVAL'::"public"."AiPolicyDecision"
               OR ("approvalId" IS NOT NULL AND "executedAt" IS NULL)),
    CONSTRAINT "ai_tool_executions_allow_executed_check"
        CHECK ("decision" <> 'ALLOW'::"public"."AiPolicyDecision"
               OR "executedAt" IS NOT NULL),
    CONSTRAINT "ai_tool_executions_allow_consequential_needs_approval_check"
        CHECK ("decision" <> 'ALLOW'::"public"."AiPolicyDecision"
               OR NOT "consequentialAction"
               OR "approvalId" IS NOT NULL),
    CONSTRAINT "ai_tool_executions_deny_check"
        CHECK ("decision" <> 'DENY'::"public"."AiPolicyDecision"
               OR ("executedAt" IS NULL AND "denyReason" IS NOT NULL
                   AND length(btrim("denyReason")) > 0)),
    -- A forbidden action is DENIED and cannot have been run, whatever the decision
    -- column claims. ADR-0007 Part 2: forbidden actions are "denied and recorded,
    -- always".
    CONSTRAINT "ai_tool_executions_forbidden_denied_check"
        CHECK ("actionClass" <> 'FORBIDDEN'
               OR ("decision" = 'DENY'::"public"."AiPolicyDecision"
                   AND "executedAt" IS NULL)),
    -- An answerable action may never need an approval; asking for one is the bug
    -- this refuses.
    CONSTRAINT "ai_tool_executions_answerable_needs_no_approval_check"
        CHECK ("actionClass" <> 'ANSWERABLE' OR "approvalId" IS NULL),
    CONSTRAINT "ai_tool_executions_latency_check"
        CHECK ("latencyMs" IS NULL OR "latencyMs" >= 0),
    CONSTRAINT "ai_tool_executions_arguments_shape_check"
        CHECK (jsonb_typeof("argumentsRedacted") = 'object'),
    CONSTRAINT "ai_tool_executions_result_shape_check"
        CHECK (jsonb_typeof("resultRedacted") = 'object'),
    CONSTRAINT "ai_tool_executions_principal_check"
        CHECK ("principalType" IN ('USER', 'AI_AGENT', 'JOB', 'SERVICE', 'SYSTEM')),
    -- An execution must say who asked and when. A run with no requestedAt cannot
    -- be placed on a timeline.
    CONSTRAINT "ai_tool_executions_requested_at_check"
        CHECK ("requestedAt" IS NOT NULL)
);

ALTER TABLE ONLY "public"."ai_tool_executions"
    ADD CONSTRAINT "ai_tool_executions_run_tenant_fkey"
    FOREIGN KEY ("aiRunId", "organizationId")
    REFERENCES "public"."ai_runs"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_tool_executions"
    ADD CONSTRAINT "ai_tool_executions_approval_tenant_fkey"
    FOREIGN KEY ("approvalId", "organizationId")
    REFERENCES "public"."ai_approvals"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."ai_tool_executions"
    ADD CONSTRAINT "ai_tool_executions_agent_version_tenant_fkey"
    FOREIGN KEY ("agentVersionId", "organizationId")
    REFERENCES "public"."ai_agent_versions"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX "ai_tool_executions_organizationId_run_idx"
    ON "public"."ai_tool_executions" USING "btree"
       ("organizationId", "aiRunId");

-- The Phase-7 Admin question: "which tools are being refused, and why".
CREATE INDEX "ai_tool_executions_organizationId_decision_idx"
    ON "public"."ai_tool_executions" USING "btree"
       ("organizationId", "decision", "createdAt");

CREATE INDEX "ai_tool_executions_organizationId_tool_idx"
    ON "public"."ai_tool_executions" USING "btree"
       ("organizationId", "toolName", "createdAt");

-- The acceptance query for "a disabled tool is denied and recorded", narrowed to
-- the refusals.
CREATE INDEX "ai_tool_executions_denied_idx"
    ON "public"."ai_tool_executions" USING "btree"
       ("organizationId", "toolName", "createdAt")
    WHERE "decision" = 'DENY';

-- ---------------------------------------------------------------------
-- Post-condition guard.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    missing        text;
    leaked_column  text;
    ok_tables      text[] := ARRAY[
        'ai_policies', 'ai_policy_versions', 'ai_model_registry',
        'ai_budgets', 'ai_budget_consumptions', 'ai_approvals',
        'ai_approval_decisions', 'ai_redaction_records',
        'ai_provenance_records', 'ai_tool_registry', 'ai_tool_executions'];
    append_only    text[] := ARRAY[
        'ai_policy_versions', 'ai_budget_consumptions',
        'ai_approval_decisions', 'ai_redaction_records',
        'ai_provenance_records', 'ai_tool_executions'];
    mutable_tables text[] := ARRAY[
        'ai_policies', 'ai_model_registry', 'ai_budgets', 'ai_approvals',
        'ai_tool_registry'];
BEGIN
    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(ARRAY[
               'ai_runs_id_organizationId_key',
               'ai_agents_id_organizationId_key',
               'ai_agent_versions_id_organizationId_key',
               'ai_tool_registry_id_organizationId_key',
               'ai_tool_registry_organizationId_toolName_key',
               'ai_tool_registry_organizationId_enabled_idx',
               'ai_policies_id_organizationId_key',
               'ai_policies_organizationId_projectId_key_key',
               'ai_policies_organizationId_projectId_enabled_idx',
               'ai_policy_versions_id_organizationId_key',
               'ai_policy_versions_policy_version_key',
               'ai_policy_versions_one_approved_per_policy',
               'ai_policy_versions_organizationId_policy_idx',
               'ai_model_registry_id_organizationId_key',
               'ai_model_registry_organizationId_provider_model_key',
               'ai_model_registry_organizationId_enabled_idx',
               'ai_budgets_id_organizationId_key',
               'ai_budgets_organizationId_projectId_key_period_key',
               'ai_budgets_organizationId_enabled_idx',
               'ai_budget_consumptions_id_organizationId_key',
               'ai_budget_consumptions_budget_run_period_key',
               'ai_budget_consumptions_organizationId_budget_period_idx',
               'ai_approvals_id_organizationId_key',
               'ai_approvals_one_pending_per_run_action',
               'ai_approvals_organizationId_projectId_status_idx',
               'ai_approvals_organizationId_run_idx',
               'ai_approval_decisions_organizationId_approval_idx',
               'ai_redaction_records_id_organizationId_key',
               'ai_redaction_records_organizationId_run_idx',
               'ai_redaction_records_organizationId_rule_idx',
               'ai_provenance_records_id_organizationId_key',
               'ai_provenance_records_run_key',
               'ai_provenance_records_organizationId_decidedAt_idx',
               'ai_tool_executions_organizationId_run_idx',
               'ai_tool_executions_organizationId_decision_idx',
               'ai_tool_executions_organizationId_tool_idx',
               'ai_tool_executions_denied_idx'
           ]) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = x);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005100000 failed: missing indexes: %', missing;
    END IF;

    SELECT string_agg(t, ', ')
      INTO missing
      FROM unnest(ok_tables) AS t
     WHERE to_regclass('public.' || quote_ident(t)) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V20261005100000 failed: missing tables: %', missing;
    END IF;

    -- 1. NO SECRETS IN THE REGISTRY. A provider row that can hold an api key is a
    --    row that will eventually hold one in a log. Phase 3's finding was that
    --    credentials stay encrypted in channel_connections and are write-only
    --    through the API.
    SELECT string_agg(c.relname || '.' || a.attname, ', ')
      INTO leaked_column
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND lower(a.attname) IN ('apikey', 'api_key', 'token', 'secret',
                                'password', 'clientsecret', 'accesstoken',
                                'bearertoken', 'privatekey', 'credential',
                                'credentials');

    IF leaked_column IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005100000 failed: credential-shaped column(s) % in the AI governance '
            'tables. The registry is a registry; credentials live in channel_connections '
            'and are write-only through the API.', leaked_column;
    END IF;

    -- 2. NO RAW TOOL ARGUMENTS OR RESULTS. FR-10.6: only the redacted form is
    --    stored, and a column that holds the raw form would be exactly the leak
    --    the requirement exists to prevent.
    SELECT string_agg(c.relname || '.' || a.attname, ', ')
      INTO leaked_column
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND (lower(a.attname) LIKE '%raw%'
            OR lower(a.attname) LIKE '%original%'
            OR lower(a.attname) LIKE '%unredacted%');

    IF leaked_column IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005100000 failed: raw/unredacted column(s) % in the AI governance '
            'tables. FR-10.6 permits only the redacted form.', leaked_column;
    END IF;

    -- 3. NO CHAIN-OF-THOUGHT (FR-5.4, ADR-0007 rule 4). Nothing that would
    --    expose internal reasoning to a customer or an operator UI.
    SELECT string_agg(c.relname || '.' || a.attname, ', ')
      INTO leaked_column
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(ok_tables))
       AND (lower(a.attname) LIKE '%chainofthought%'
            OR lower(a.attname) LIKE '%thinking%'
            OR lower(a.attname) LIKE '%reasoning%'
            OR lower(a.attname) LIKE '%scratchpad%'
            OR lower(a.attname) LIKE '%monologue%'
            OR lower(a.attname) LIKE '%deliberation%');

    IF leaked_column IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005100000 failed: chain-of-thought column(s) % in the AI governance '
            'tables. FR-5.4 and ADR-0007 rule 4 forbid storing internal reasoning.',
            leaked_column;
    END IF;

    -- 4. DENY BY DEFAULT MUST BE LITERAL. Not "no row is currently enabled" --
    --    the DEFAULT expression itself, because flipping it would break every
    --    existing deployment's assumption at the same instant.
    IF (SELECT pg_get_expr(d.adbin, d.adrelid)
          FROM pg_attrdef d
          JOIN pg_class c ON c.oid = d.adrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.adnum
         WHERE n.nspname = 'public' AND c.relname = 'ai_tool_registry'
           AND a.attname = 'isEnabled')
       IS DISTINCT FROM 'false' THEN
        RAISE EXCEPTION
            'V20261005100000 failed: ai_tool_registry."isEnabled" must default to false. '
            'Deny-by-default is a property of the schema, not of a convention.';
    END IF;

    -- 5. APPEND-ONLY SHAPE, as a map rather than one flag.
    SELECT string_agg(c.relname, ', ')
      INTO missing
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.attname = 'updatedAt'
                              AND a.attnum > 0 AND NOT a.attisdropped
     WHERE n.nspname = 'public'
       AND c.relname IN (SELECT unnest(append_only));

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005100000 failed: these tables are append-only and must not have '
            'updatedAt: %', missing;
    END IF;

    SELECT string_agg(x, ', ')
      INTO missing
      FROM unnest(mutable_tables) AS x
     WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = x
                         AND a.attname = 'updatedAt'
                         AND a.attnum > 0 AND NOT a.attisdropped);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261005100000 failed: these tables are mutable state machines and must '
            'have updatedAt: %', missing;
    END IF;

    -- 6. FR-1.2 on all eleven.
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
            'V20261005100000 failed: organizationId must be NOT NULL (FR-1.2) on: %', missing;
    END IF;

    -- 7. V6 contract: no timestamptz.
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
            'V20261005100000 failed: timestamptz column in: %', missing;
    END IF;

    -- 8. Every UTC-writing default must say UTC.
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
            'V20261005100000 failed: a default uses a session-local CURRENT_TIMESTAMP: %',
            missing;
    END IF;

    -- 9. Enum value lists ARE the contract.
    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AiPolicyDecision')
       IS DISTINCT FROM 'ALLOW,REQUIRE_APPROVAL,DENY' THEN
        RAISE EXCEPTION 'V20261005100000 failed: public.AiPolicyDecision value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AiApprovalStatus')
       IS DISTINCT FROM 'PENDING,APPROVED,REJECTED,EXPIRED,REVOKED' THEN
        RAISE EXCEPTION 'V20261005100000 failed: public.AiApprovalStatus value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AiRiskLevel')
       IS DISTINCT FROM 'LOW,MEDIUM,HIGH,CRITICAL' THEN
        RAISE EXCEPTION 'V20261005100000 failed: public.AiRiskLevel value list drifted.';
    END IF;

    IF (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
          FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public' AND t.typname = 'AiBudgetPeriod')
       IS DISTINCT FROM 'DAILY,MONTHLY' THEN
        RAISE EXCEPTION 'V20261005100000 failed: public.AiBudgetPeriod value list drifted.';
    END IF;

    -- 10. THE PARTIAL "ONE APPROVED POLICY VERSION" INDEX MUST BE PARTIAL. A
    --     unique index without the WHERE clause would forbid authoring a second
    --     version at all, which is the authoring deadlock Phase 6a found and had
    --     to fix by adding a DRAFT state.
    IF NOT EXISTS (SELECT 1 FROM pg_index i
                     JOIN pg_class c ON c.oid = i.indexrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relname = 'ai_policy_versions_one_approved_per_policy'
                      AND i.indisunique AND i.indpred IS NOT NULL) THEN
        RAISE EXCEPTION
            'V20261005100000 failed: ai_policy_versions_one_approved_per_policy must be a '
            'partial UNIQUE index, otherwise a second policy version cannot be authored.';
    END IF;

    -- 11. The "one pending approval per run and action" index must be PARTIAL for
    --     the same reason: a unique index over all statuses would refuse a
    --     legitimate re-request after a rejection.
    IF NOT EXISTS (SELECT 1 FROM pg_index i
                     JOIN pg_class c ON c.oid = i.indexrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relname = 'ai_approvals_one_pending_per_run_action'
                      AND i.indisunique AND i.indpred IS NOT NULL) THEN
        RAISE EXCEPTION
            'V20261005100000 failed: ai_approvals_one_pending_per_run_action must be a '
            'partial UNIQUE index scoped to PENDING.';
    END IF;

    -- 12. THE COMPOSITE TENANT KEYS INTO THE EXISTING AGENT MODEL MUST EXIST,
    --     otherwise a provenance row or a tool execution may name another
    --     tenant's run.
    IF (SELECT count(*) FROM pg_constraint con
          JOIN pg_class c ON c.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND con.conname IN ('ai_provenance_records_run_tenant_fkey',
                               'ai_provenance_records_agent_tenant_fkey',
                               'ai_provenance_records_agent_version_tenant_fkey',
                               'ai_tool_executions_run_tenant_fkey',
                               'ai_tool_executions_agent_version_tenant_fkey',
                               'ai_approvals_run_tenant_fkey',
                               'ai_redaction_records_run_tenant_fkey',
                               'ai_budget_consumptions_run_tenant_fkey')
           AND con.contype = 'f') <> 8 THEN
        RAISE EXCEPTION
            'V20261005100000 failed: all eight composite tenant foreign keys into the '
            'existing agent model must exist.';
    END IF;

    -- 13. Truncation detection.
    IF EXISTS (SELECT 1 FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'ai_provenance_records'
                  AND indexname NOT IN (
                      'ai_provenance_records_pkey',
                      'ai_provenance_records_id_organizationId_key',
                      'ai_provenance_records_run_key',
                      'ai_provenance_records_organizationId_decidedAt_idx')) THEN
        RAISE EXCEPTION
            'V20261005100000 failed: an unexpected index exists on ai_provenance_records '
            '-- most likely a name longer than 63 bytes was silently truncated.';
    END IF;
END
$$;