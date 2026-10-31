-- =====================================================================
-- undo_phase7_10a_seed.sql
--
-- SYNTHETIC rows used ONLY to prove that undo_phase7_10a.sql REFUSES to run
-- against a slice that holds data. Never run against `firstelement`, and never
-- run outside a throwaway database.
--
-- This is a REHEARSAL asset, not a migration: the filename does not match
-- Flyway's `V*.sql` pattern and it does not live in ops/flyway/sql/, so neither
-- Flyway nor the Spring boot nor the Testcontainers fixture can execute it.
--
-- Every value is synthetic: organization slugs and user e-mails are on
-- example.test, amounts are round numbers, and every string is prefixed `undo-`
-- so a leaked rehearsal row is recognisable for what it is.
--
-- IT IS IDEMPOTENT (`ON CONFLICT DO NOTHING` on every insert). The first
-- version was not, and re-running it after a partial run died with
--     duplicate key value violates unique constraint "organizations_pkey"
-- which meant a rehearsal could not be repeated on a database somebody else had
-- already touched. A rehearsal asset that can only be run once is an asset
-- nobody re-runs, and a rehearsal nobody re-runs is worth less than the write-up
-- of it. The final evidence queries report what is actually present, so a
-- partially-seeded run is visible rather than assumed.
--
-- One row per slice table that can hold one without more setup, chosen so the
-- refusal message covers every concern:
--   * legal hold      -> the state ADR-0008 rule 5 depends on
--   * privacy request -> the ADR-0007 / FR-7 deletion state machine
--   * export job      -> the export state machine and its pinned report version
--   * usage event     -> the immutable ledger (money)
--   * rate card       -> the versioned, effective-dated pricing source
--   * rollup snapshot -> the pinned, derived revenue
--   * AI tool + exec  -> the deny-by-default registry and a recorded refusal
-- =====================================================================

-- --- tenant, project, user ---------------------------------------------
INSERT INTO "public"."organizations" (id, name, slug, "createdAt", "updatedAt")
VALUES ('11111111-1111-1111-1111-111111111111', 'undo-tenant', 'undo-tenant',
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."projects" (id, "organizationId", name, slug, "createdAt", "updatedAt")
VALUES ('22222222-2222-2222-2222-222222222222',
        '11111111-1111-1111-1111-111111111111',
        'undo-project', 'undo-project',
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."users" (id, "organizationId", email, "passwordHash", "fullName",
                              role, locale, "createdAt", "updatedAt")
VALUES ('33333333-3333-3333-3333-333333333333',
        '11111111-1111-1111-1111-111111111111',
        'undo-operator@example.test', 'not-a-real-hash', 'undo Operator',
        'EMPLOYEE'::"public"."UserRole", 'ar'::"public"."Locale",
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

-- --- audit / legal hold / privacy --------------------------------------
INSERT INTO "public"."legal_holds"
    (id, "organizationId", "scopeType", reason, status, "placedAt", "placedByUserId",
     "createdAt", "updatedAt")
VALUES ('44444444-4444-4444-4444-444444444444',
        '11111111-1111-1111-1111-111111111111',
        'TENANT'::"public"."LegalHoldScopeType", 'undo rehearsal hold', 'ACTIVE',
        (now() AT TIME ZONE 'UTC'), '33333333-3333-3333-3333-333333333333',
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."admin_audit_events"
    (id, "organizationId", "actorUserId", "principalType", "principalName",
     action, "resourceType", outcome, "retentionClass", "occurredAt", "createdAt")
VALUES ('55555555-5555-5555-5555-555555555555',
        '11111111-1111-1111-1111-111111111111',
        '33333333-3333-3333-3333-333333333333',
        'USER'::"public"."AuditPrincipalType", 'undo-operator@example.test',
        'undo.rehearsal.write', 'Rehearsal', 'SUCCESS'::"public"."AuditOutcome",
        'AUDIT_STANDARD',
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."privacy_deletion_requests"
    (id, "organizationId", "subjectUserId", "subjectType", status, reason,
     "requestedAt", "evidenceReference", "createdAt", "updatedAt")
VALUES ('66666666-6666-6666-6666-666666666666',
        '11111111-1111-1111-1111-111111111111',
        '33333333-3333-3333-3333-333333333333', 'USER',
        'REQUESTED'::"public"."PrivacyDeletionRequestStatus", 'undo rehearsal request',
        (now() AT TIME ZONE 'UTC'), 'undo-evidence',
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

-- --- report + export ---------------------------------------------------
INSERT INTO "public"."report_definitions"
    (id, "organizationId", "key", "nameAr", "nameEn", "timezone",
     "redactionPolicy", "retentionClass", "createdAt", "updatedAt")
VALUES ('77777777-7777-7777-7777-777777777777',
        '11111111-1111-1111-1111-111111111111',
        'undo_report', 'تقرير تدريبي', 'undo rehearsal report', 'Asia/Riyadh',
        'undo-policy', 'REPORTS_STANDARD',
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."export_jobs"
    (id, "organizationId", "projectId", "requestedByUserId", "format", status,
     "redactionPolicy", "retentionClass", "authorizationSnapshot",
     "expiresAt", "requestedAt", "createdAt", "updatedAt")
VALUES ('88888888-8888-8888-8888-888888888888',
        '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222',
        '33333333-3333-3333-3333-333333333333',
        'CSV'::"public"."ExportFormat", 'QUEUED'::"public"."ExportJobStatus",
        'undo-policy', 'EXPORTS_STANDARD',
        '{"undo":{"role":"EMPLOYEE"}}'::jsonb,
        (now() AT TIME ZONE 'UTC') + interval '7 days',
        (now() AT TIME ZONE 'UTC'),
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

-- --- usage ledger + finance -------------------------------------------
-- occurredAt MUST fall inside the period the row claims:
-- usage_events_period_contains_event_check refuses an event dated outside its own
-- billing period, because a period boundary that moves after the fact is how a
-- locked period stops agreeing with its own events. The first version of this
-- seed used now() against a January period and the CHECK refused it, which is the
-- constraint working -- and is why the rehearsal seed dates its rows rather than
-- using the current timestamp.
INSERT INTO "public"."usage_events"
    (id, "organizationId", "projectId", dimension, quantity, unit,
     "sourceEventType", "sourceEventId", "principalType", "principalName",
     "occurredAt", "periodStart", "periodEnd", currency, "costAmount", "createdAt")
VALUES ('99999999-9999-9999-9999-999999999999',
        '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222',
        'AI_TOKEN'::"public"."UsageDimension", 1000, 'tokens',
        'undo.Rehearsal', '99999999-9999-9999-9999-999999999999',
        'SYSTEM', 'undo-rehearsal',
        timestamp '2026-01-15 12:00:00',
        date '2026-01-01', date '2026-01-31',
        'USD', 1.000000,
        (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."rate_cards"
    (id, "organizationId", "key", "nameAr", "nameEn", currency, status,
     "createdAt", "updatedAt")
VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        '11111111-1111-1111-1111-111111111111',
        'undo_card', 'بطاقة تدريبية', 'undo card', 'USD',
        'DRAFT'::"public"."RateCardStatus",
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."rate_card_versions"
    (id, "organizationId", "rateCardId", "versionNumber", currency, "markupRate",
     "effectiveFrom", status, "createdByUserId", "createdAt")
VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        '11111111-1111-1111-1111-111111111111',
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 1, 'USD', 2.000000,
        date '2026-01-01', 'DRAFT'::"public"."RateCardStatus",
        '33333333-3333-3333-3333-333333333333',
        (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."finance_periods"
    (id, "organizationId", "key", "nameAr", "nameEn", "periodStart", "periodEnd",
     currency, status, "createdAt", "updatedAt")
VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc',
        '11111111-1111-1111-1111-111111111111',
        '2026-01', 'يناير 2026', 'January 2026', date '2026-01-01', date '2026-01-31',
        'USD', 'OPEN'::"public"."PeriodStatus",
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

-- revenueAmount MUST equal round(costAmount * markupRate, 6): 10 * 2 = 20. This
-- row is the reference case the fixture's derivation assertion is proved against
-- -- if the CHECK were dropped and somebody typed a revenue by hand, THIS row
-- would stop matching its own inputs.
INSERT INTO "public"."usage_rollup_snapshots"
    (id, "organizationId", "projectId", "periodId", grain, "bucketStart", "bucketEnd",
     dimension, currency, "sourceEventCount", quantity, "costAmount",
     "revenueAmount", "markupRate", "rateCardVersionId", generation,
     "computedAt", "computedByPrincipal", "createdAt")
VALUES ('dddddddd-dddd-dddd-dddd-dddddddddddd',
        '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222',
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        'MONTHLY'::"public"."RollupGrain", date '2026-01-01', date '2026-01-31',
        'AI_TOKEN'::"public"."UsageDimension", 'USD',
        1, 1000.000000, 10.000000, 20.000000, 2.000000,
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 1,
        (now() AT TIME ZONE 'UTC'), 'undo-rehearsal',
        (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

-- --- AI policy: a DISABLED tool, recorded as disabled, and a refusal ------
INSERT INTO "public"."ai_tool_registry"
    (id, "organizationId", "toolName", "requiredPermission", "isEnabled",
     "disabledAt", "disabledByUserId", "disabledReason", "riskLevel", "createdAt", "updatedAt")
VALUES ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
        '11111111-1111-1111-1111-111111111111',
        'undo_tool', 'tool.undo', false,
        (now() AT TIME ZONE 'UTC'), '33333333-3333-3333-3333-333333333333',
        'undo rehearsal: registered and left disabled',
        'LOW'::"public"."AiRiskLevel",
        (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

INSERT INTO "public"."ai_tool_executions"
    (id, "organizationId", "projectId", "toolName", "actionClass",
     "consequentialAction", decision, "denyReason", "principalType",
     "requestedAt", "createdAt")
VALUES ('ffffffff-ffff-ffff-ffff-ffffffffffff',
        '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222',
        'undo_tool', 'ANSWERABLE', false,
        'DENY'::"public"."AiPolicyDecision", 'tool is disabled',
        'AI_AGENT', (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- Evidence: the rehearsal rows exist, and they are all synthetic.
-- ---------------------------------------------------------------------
SELECT 'undo_seed_rows' AS check_name,
       (SELECT count(*) FROM "public"."legal_holds")
     + (SELECT count(*) FROM "public"."admin_audit_events")
     + (SELECT count(*) FROM "public"."privacy_deletion_requests")
     + (SELECT count(*) FROM "public"."report_definitions")
     + (SELECT count(*) FROM "public"."export_jobs")
     + (SELECT count(*) FROM "public"."usage_events")
     + (SELECT count(*) FROM "public"."rate_cards")
     + (SELECT count(*) FROM "public"."rate_card_versions")
     + (SELECT count(*) FROM "public"."finance_periods")
     + (SELECT count(*) FROM "public"."usage_rollup_snapshots")
     + (SELECT count(*) FROM "public"."ai_tool_registry")
     + (SELECT count(*) FROM "public"."ai_tool_executions") AS rows_seeded;

SELECT 'undo_seed_tenants' AS check_name, count(*) AS rehearsal_tenants
  FROM "public"."organizations"
 WHERE slug LIKE 'undo-%';

SELECT 'undo_seed_money_is_consistent' AS check_name, count(*) AS inconsistent_rows
  FROM "public"."usage_rollup_snapshots"
 WHERE "revenueAmount" IS NOT NULL
   AND "revenueAmount" <> round("costAmount" * "markupRate", 6);

SELECT 'undo_seed_disabled_tool_is_stored_as_disabled' AS check_name, count(*) AS stored
  FROM "public"."ai_tool_registry"
 WHERE NOT "isEnabled"
   AND "disabledAt" IS NOT NULL
   AND "disabledByUserId" IS NOT NULL
   AND "disabledReason" IS NOT NULL;