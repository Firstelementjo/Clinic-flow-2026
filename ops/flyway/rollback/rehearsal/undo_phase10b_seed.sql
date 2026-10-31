-- =====================================================================
-- undo_phase10b_seed.sql
--
-- REHEARSAL-ONLY. Seeds rows into a THROWAWAY database to prove that
-- ops/flyway/rollback/undo_phase10b.sql REFUSES on data -- and that the refusal
-- is a refusal and not a message (ADR-0009 section 5).
--
-- NEVER RUN THIS AGAINST `firstelement` OR ANY REAL DATABASE. It sits in
-- ops/flyway/rollback/rehearsal/ beside undo_phase7_10a_seed.sql for the same
-- reason.
--
-- HOW TO SELECT WHAT TO SEED
--
--   The flags are psql VARIABLES and they are PRESENCE-tested, not
--   value-tested. That is not laziness: psql's \if evaluates a BOOLEAN
--   expression, so `\if :mode = 'keys'` is parsed as the string "keys = keys"
--   and fails with `unrecognized value "keys = keys" for "\if expression"`.
--   The throwaway rehearsal hit exactly that. `\if :{?keys}` is the idiom
--   undo_phase7_10a.sql already uses and the only one that works.
--
--     -v all=on       every guard at once (the default when no flag is given)
--     -v keys=on      one ACTIVE api_key                 -> guard 4 (data gate)
--     -v narrow=on    one RECORD-scoped legal hold       -> guard 5a (D4)
--     -v customer=on  one CUSTOMER-subject request       -> guard 5b (D5)
--     -v decided=on   one REJECTED approval WITH its
--                     decision record                    -> guard 5c (D1)
--
--   Each flag seeds ONLY its own trigger, so a refusal names exactly one cause.
--
--   Example:
--     Get-Content -Raw ops\flyway\rollback\rehearsal\undo_phase10b_seed.sql |
--       docker exec -i firstelement-postgres psql -U firstelement `
--         -d firstelement_p10b_undo -v ON_ERROR_STOP=1 -v narrow=on -f -
-- =====================================================================

\set ON_ERROR_STOP on

-- The default: no flag at all means "seed everything, trip every guard".
\if :{?keys}
\else
  \if :{?narrow}
  \else
    \if :{?customer}
    \else
      \if :{?decided}
      \else
        \set keys 1
        \set narrow 1
        \set customer 1
        \set decided 1
        \set seeded_all 1
      \endif
    \endif
  \endif
\endif

\if :{?all}
  \set keys 1
  \set narrow 1
  \set customer 1
  \set decided 1
  \set seeded_all 1
\endif

-- One tenant, one project, one user, one customer. Fixed ids, so a rerun is
-- idempotent and two runs of this script cannot collide.
INSERT INTO public.organizations (id, name, slug, "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b001', 'undo-10b-org', 'undo-10b-org',
        current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.projects (id, "organizationId", name, slug, "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b002',
        '00000000-0000-4000-a000-00000000b001', 'undo-10b-project', 'undo-10b-project',
        current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.users (id, "organizationId", email, "passwordHash", "fullName",
                          role, locale, "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b003',
        '00000000-0000-4000-a000-00000000b001', 'undo-10b@example.test',
        'not-a-real-hash', 'Undo 10b', 'ADMIN'::"UserRole", 'ar'::"Locale",
        current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.customers (id, "organizationId", "projectId", "cxId", "displayName",
                              "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b004',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        'CXUN10B', 'Undo 10b Customer', current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- -v keys: one service principal and one ACTIVE api key.
--
-- The keyHash is a SHAPE, not a secret: a $argon2id$-prefixed string of a length
-- that satisfies firstelement_api_key_hash_guard, which refuses anything shorter
-- than 32 characters or lacking a '$' algorithm delimiter. No real credential
-- exists or could exist here -- this is a fixture in a throwaway database, and
-- the string is not a digest of anything.
-- ---------------------------------------------------------------------------
\if :{?keys}
INSERT INTO public.service_principals (id, "organizationId", "projectId", "principalKey",
                                        "displayName", status, capabilities,
                                        "createdByUserId", "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b010',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        'undo-10b-connector', 'Undo 10b connector', 'ACTIVE'::"public"."ServicePrincipalStatus",
        '["conversation:read"]'::jsonb,
        '00000000-0000-4000-a000-00000000b003',
        current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.api_keys (id, "organizationId", "projectId", "servicePrincipalId", name,
                             "keyPrefix", "keyHash", "hashAlgorithm", scopes, status,
                             "createdByUserId", "expiresAt", "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b011',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        '00000000-0000-4000-a000-00000000b010', 'undo-10b key',
        'fe_live_undo10b',
        '$argon2id$v=19$m=65536,t=3,p=4$AAAAAAAAAAAAAAAA$BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB',
        'argon2id', '["conversation:read"]'::jsonb, 'ACTIVE'::"public"."ApiKeyStatus",
        '00000000-0000-4000-a000-00000000b003',
        (now() at time zone 'UTC') + interval '30 days',
        (now() at time zone 'UTC'), (now() at time zone 'UTC'))
ON CONFLICT (id) DO NOTHING;

SELECT 'SEEDED keys: api_keys=' || (SELECT count(*) FROM public.api_keys)
            || ' service_principals=' || (SELECT count(*) FROM public.service_principals)
            || ' audit_rows=' || (SELECT count(*) FROM public.admin_audit_events
                                   WHERE "resourceType" = 'api_keys') AS seed_report;
\endif

-- ---------------------------------------------------------------------------
-- -v narrow: one RECORD-scoped legal hold.
--
-- Insertable ONLY because of V20261006130000 -- before it,
-- legal_holds_scope_pair_check had no arm for RECORD and the insert was refused.
-- Its presence is therefore the proof that D4 landed, AND it is what guard 5a
-- refuses the undo over, because the original CHECK could not hold it.
-- ---------------------------------------------------------------------------
\if :{?narrow}
INSERT INTO public.legal_holds (id, "organizationId", "scopeType", "scopeRef", reason, status,
                                "placedAt", "placedByUserId", "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b020',
        '00000000-0000-4000-a000-00000000b001',
        'RECORD'::"public"."LegalHoldScopeType",
        'conversation:00000000-0000-4000-a000-00000000b099',
        'undo rehearsal narrow hold', 'ACTIVE',
        (now() at time zone 'UTC'), '00000000-0000-4000-a000-00000000b003',
        (now() at time zone 'UTC'), (now() at time zone 'UTC'))
ON CONFLICT (id) DO NOTHING;

SELECT 'SEEDED narrow: legal_holds=' || (SELECT count(*) FROM public.legal_holds) AS seed_report;
\endif

-- ---------------------------------------------------------------------------
-- -v customer: one CUSTOMER-subject privacy request.
--
-- Insertable ONLY because of V20261006140000: before it "subjectUserId" was NOT
-- NULL with a composite FK to users, and a customer is not a users row -- which
-- is the defect. Its presence is the proof that D5 landed, and guard 5b refuses
-- the undo because dropping subjectCustomerId discards WHICH customer the
-- request was about, and that is not derivable from anything else.
-- ---------------------------------------------------------------------------
\if :{?customer}
INSERT INTO public.privacy_deletion_requests (id, "organizationId", "projectId",
                                               "subjectCustomerId", "subjectType", status,
                                               reason, "requestedAt", "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b030',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        '00000000-0000-4000-a000-00000000b004', 'CUSTOMER',
        'REQUESTED'::"public"."PrivacyDeletionRequestStatus",
        'undo rehearsal customer deletion',
        (now() at time zone 'UTC'), (now() at time zone 'UTC'), (now() at time zone 'UTC'))
ON CONFLICT (id) DO NOTHING;

SELECT 'SEEDED customer: customer-subject requests=' ||
       (SELECT count(*) FROM public.privacy_deletion_requests
         WHERE "subjectCustomerId" IS NOT NULL) AS seed_report;
\endif

-- ---------------------------------------------------------------------------
-- -v decided: one REJECTED approval that KEEPS its decision record.
--
-- This is the ADR-0011 section 1 defect itself, as data. Insertable only because
-- V20261006100000 landed; its presence is the proof that D1 landed, and guard
-- 5c refuses the undo because restoring the original CHECK would mean CLEARING
-- the decision -- the data loss the batch exists to prevent.
--
-- ai_approvals needs an ai_runs row for its composite tenant FK, and ai_runs in
-- turn needs an ai_agent_versions row (agentVersionId is NOT NULL), a customer
-- and a conversation. All five are seeded with it.
--
-- Every column name below was READ OFF THE CATALOG rather than guessed. The
-- first version of this script assumed ai_agents had a "version" column and that
-- ai_runs had a "createdAt", and the rehearsal refused both statements -- which
-- is the correct outcome for a rehearsal-only fixture, and the reason the
-- rehearsal seeds rows instead of only reading the catalog.
-- ---------------------------------------------------------------------------
\if :{?decided}
INSERT INTO public.ai_agents (id, "organizationId", "projectId", name, "isEnabled",
                              "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b040',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        'undo-10b-agent', true, current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.ai_agent_versions (id, "organizationId", "projectId", "agentId",
                                      version, "systemInstructions", provider, temperature,
                                      "maxTokens", "failoverChain", "enabledTools",
                                      "knowledgeBaseIds", "minRetrievalScore",
                                      "memoryWindow", "createdByUserId", "createdAt")
VALUES ('00000000-0000-4000-a000-00000000b045',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        '00000000-0000-4000-a000-00000000b040', 1, 'undo rehearsal agent version',
        'LOCAL'::"AIProviderKind", 0.0, 512,
        '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 0.0, 8,
        '00000000-0000-4000-a000-00000000b003', (now() at time zone 'UTC'))
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.customers (id, "organizationId", "projectId", "cxId", "displayName",
                              "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b046',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        'CXUN10R', 'Undo 10b Run Customer', current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.conversations (id, "organizationId", "projectId", "customerId",
                                  "ticketNumber", channel, status, "createdAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b047',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        '00000000-0000-4000-a000-00000000b046', 'T-UN10B', 'MOCK'::"ChannelType",
        'OPEN'::"ConversationStatus", current_timestamp, current_timestamp)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.ai_runs (id, "organizationId", "projectId", "conversationId", "agentId",
                            "agentVersionId", status, provider, "startedAt")
VALUES ('00000000-0000-4000-a000-00000000b041',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        '00000000-0000-4000-a000-00000000b047', '00000000-0000-4000-a000-00000000b040',
        '00000000-0000-4000-a000-00000000b045', 'RUNNING', 'LOCAL'::"AIProviderKind",
        (now() at time zone 'UTC'))
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.ai_approvals (id, "organizationId", "projectId", "aiRunId", action,
                                 "actionClass", status, "requestedByPrincipal", reason,
                                 "requestedAt", "decidedByUserId", "decidedAt", "decisionNote",
                                 "expiresAt", "updatedAt")
VALUES ('00000000-0000-4000-a000-00000000b042',
        '00000000-0000-4000-a000-00000000b001', '00000000-0000-4000-a000-00000000b002',
        '00000000-0000-4000-a000-00000000b041', 'undo-tool#run',
        'CONSEQUENTIAL', 'REJECTED', 'undo@example.test', 'undo rehearsal approval',
        (now() at time zone 'UTC'), '00000000-0000-4000-a000-00000000b003',
        (now() at time zone 'UTC'), 'refused: this is the defect as data',
        (now() at time zone 'UTC') + interval '1 hour',
        (now() at time zone 'UTC'))
ON CONFLICT (id) DO NOTHING;

SELECT 'SEEDED decided: REJECTED approvals carrying a decision=' ||
       (SELECT count(*) FROM public.ai_approvals
         WHERE status = 'REJECTED'::"public"."AiApprovalStatus"
           AND "decidedAt" IS NOT NULL) AS seed_report;
\endif
