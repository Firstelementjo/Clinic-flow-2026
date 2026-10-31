-- =====================================================================
-- dataset.sql -- synthetic performance dataset
--
-- APPLIED ONLY TO A DISPOSABLE DATABASE. Create one with:
--
--   .\ops\quality\checks\prepare-throwaway-db.ps1 `
--       -Database firstelement_perf_dataset_check -MigrateChain
--
-- and drop it afterwards with the same script plus -Dispose. That script
-- refuses the name `firstelement`, so a rehearsal can never be one typo away
-- from the development database.
--
-- NEVER run this against `firstelement`: it inserts hundreds of thousands of
-- synthetic rows.
--
-- Synthetic only. No customer name, phone, email, message body or credential
-- comes from anywhere. Every value is `perf-` prefixed or sits in the reserved
-- +0000 phone range, so a row that escapes into a log is recognisable as test
-- data. The one exception is `passwordHash`, which is now a real bcrypt hash of a
-- per-run throwaway password -- see the note at the users insert for why that is
-- safe, and why it is safer than the invalid hash it replaced.
--
-- Every column is written against the VERIFIED physical schema (quoted
-- camelCase, `timestamp without time zone`, explicit enum casts) as ADR-0002
-- requires. Volumes and the reasoning behind each are in
-- tests/performance/dataset.md.
-- =====================================================================

\set ON_ERROR_STOP on

BEGIN;


-- ---------------------------------------------------------------------
-- 2 tenants, 2 projects each. Tenant isolation is untestable with one tenant.
-- ---------------------------------------------------------------------
INSERT INTO organizations (id, name, slug, timezone, "createdAt", "updatedAt")
SELECT gen_random_uuid(), 'perf-org-' || g, 'perf-org-' || g, 'Asia/Riyadh',
       (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC')
FROM generate_series(1, 2) g;

INSERT INTO projects (id, "organizationId", name, slug, timezone, "isArchived", "createdAt", "updatedAt")
SELECT gen_random_uuid(), o.id,
       'perf-project-' || g,
       'perf-project-' || g || '-' || o.slug,
       'Asia/Riyadh', false,
       (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC')
FROM generate_series(1, 2) g
CROSS JOIN organizations o
WHERE o.slug = 'perf-org-' || g;


-- Tenant ordinal, resolved AFTER the tenants exist. This ordering is not
-- cosmetic: an earlier revision built perf_tenants before the INSERT, so
-- every downstream join matched nothing, every INSERT inserted zero rows,
-- and psql exited 0. The count query at the bottom is what caught it.
CREATE TEMP TABLE perf_tenants ON COMMIT DROP AS
SELECT o.id AS org_id,
       o.name AS org_name,
       o.slug AS org_slug,
       row_number() OVER (ORDER BY o.name) AS org_no
FROM organizations o
WHERE o.slug LIKE 'perf-org-%';

-- First project of each tenant, resolved once so the rest stays readable.
CREATE TEMP TABLE perf_first_project ON COMMIT DROP AS
SELECT DISTINCT ON ("organizationId") id AS project_id, "organizationId" AS org_id
FROM projects
ORDER BY "organizationId", name;

-- ---------------------------------------------------------------------
-- 6 users per tenant. Email is the join key for everything below.
-- ---------------------------------------------------------------------
INSERT INTO users (id, "organizationId", email, "passwordHash", "fullName", role, locale, "isActive", "createdAt", "updatedAt")
SELECT gen_random_uuid(), o.id,
       'perf-user-' || o.slug || '-' || g || '@perf.invalid',
       -- PHASE 43. This used to be a deliberately INVALID bcrypt hash, so that "nobody
       -- authenticates as a perf user on purpose". That was a good instinct and it is
       -- preserved -- but enforced differently, because the invalid hash also made the
       -- performance stage unwirable: run-smoke.ps1 needs a session and the dataset
       -- deliberately refused to provide one.
       --
       -- The property that mattered was never "the hash is invalid". It was "NO USABLE
       -- CREDENTIAL SURVIVES A RUN". That is now enforced by lifecycle rather than by an
       -- unusable value, and it is STRICTLY STRONGER, because it holds on the failure path
       -- as well as the happy one:
       --
       --   * the plaintext is generated per run, hashed by bcryptjs at cost 12, and only
       --     the resulting hash is written here -- this file never receives a plaintext;
       --   * cost 12 and the $2a$ prefix are exactly what PasswordVerifier documents it
       --     expects, because that is what the legacy hashes are;
       --   * the database this row lands in is created AND DROPPED by the wrapper that
       --     generated the plaintext, in a finally block;
       --   * so the credential stops existing the moment the run ends, including when the
       --     run fails part-way through.
       --
       -- Substituted by ops/quality/checks/run-performance-smoke.ps1, which refuses to start
       -- this file at all if the placeholder is missing, rather than writing whatever hash
       -- happens to be sitting here.
       '$fe_perf_bcrypt$',
       'perf-user-' || g,
       'ADMIN'::"UserRole",
       'ar'::"Locale",
       true,
       (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC')
FROM generate_series(1, 6) g
CROSS JOIN organizations o;

-- One employee per user, online, with capacity.
INSERT INTO employees (id, "organizationId", "userId", department, "availabilityStatus",
                       "maxConcurrentConversations", "activeConversationCount", "createdAt", "updatedAt")
SELECT gen_random_uuid(), u."organizationId", u.id, 'perf-dept-' || g,
       'ONLINE'::"EmployeeAvailabilityStatus",
       20, 0,
       (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC')
FROM generate_series(1, 6) g
JOIN users u ON u.email IN ('perf-user-perf-org-1-' || g || '@perf.invalid',
                            'perf-user-perf-org-2-' || g || '@perf.invalid');

-- Membership: a user reaches every project of their OWN tenant and no other.
INSERT INTO project_memberships (id, "organizationId", "projectId", "userId", "createdAt")
SELECT gen_random_uuid(), u."organizationId", p.id, u.id, (now() AT TIME ZONE 'UTC')
FROM users u
JOIN projects p ON p."organizationId" = u."organizationId";

-- ---------------------------------------------------------------------
-- 20 000 customers per tenant. cxId is '#33' + 6 digits (varchar(9)) and is
-- globally unique, so the number embeds the tenant ordinal: a read that
-- leaked across tenants would show a different prefix range.
-- ---------------------------------------------------------------------
INSERT INTO customers (id, "organizationId", "projectId", "cxId", "displayName", "primaryPhone",
                       "firstSeenAt", "lastSeenAt", "createdAt", "updatedAt")
SELECT c.id, c.org_id, c.project_id,
       '#33' || lpad(((c.org_no * 100000 + c.g)::bigint)::text, 6, '0'),
       'perf-customer-' || c.org_slug || '-' || c.g,
       '+0000000' || lpad(c.g::text, 4, '0'),
       (now() AT TIME ZONE 'UTC') - ((c.g % 180) || ' days')::interval,
       (now() AT TIME ZONE 'UTC') - ((c.g % 180) || ' days')::interval,
       (now() AT TIME ZONE 'UTC') - ((c.g % 180) || ' days')::interval,
       (now() AT TIME ZONE 'UTC') - ((c.g % 180) || ' days')::interval
FROM (
    SELECT gen_random_uuid() AS id,
           t.org_id, t.org_slug, t.org_no,
           p.project_id,
           g
    FROM perf_tenants t
    JOIN perf_first_project p ON p.org_id = t.org_id
    CROSS JOIN generate_series(1, 20000) g
) c;

-- One verified channel identity per customer: identity resolution is on the
-- hot path of every inbound message.
INSERT INTO customer_identities (id, "organizationId", "projectId", "customerId",
                                 platform, "externalId", "displayName", "isVerified", "createdAt", "updatedAt")
SELECT ci.id, ci."organizationId", ci."projectId", ci."customerId",
       'WHATSAPP'::"ChannelType",
       ci."externalId",
       'perf-handle',
       true,
       ci."createdAt", ci."createdAt"
FROM (
    SELECT gen_random_uuid() AS id,
           c."organizationId", c."projectId", c.id AS "customerId",
           'perf-handle-' || c.id::text AS "externalId",
           c."createdAt"
    FROM customers c
) ci;

-- ---------------------------------------------------------------------
-- 2 conversations per customer = 40 000.
-- ticketNumber is '#MMDD' + 9 digits (varchar(14)) and is generated from a
-- global sequence so the unique index is not the bottleneck being measured.
-- ---------------------------------------------------------------------
INSERT INTO conversations (id, "organizationId", "projectId", "customerId", "ticketNumber",
                           channel, status, mode, priority, subject,
                           "assignedEmployeeId", "messageCount", "lastMessageAt", "createdAt", "updatedAt")
SELECT cv.id, cv."organizationId", cv."projectId", cv."customerId", cv."ticketNumber",
       'WHATSAPP'::"ChannelType",
       (ARRAY['OPEN','PENDING','RESOLVED','CLOSED'])[1 + (cv.g % 4)]::"ConversationStatus",
       'HUMAN'::"ConversationMode",
       (ARRAY['LOW','NORMAL','HIGH','URGENT'])[1 + (cv.g % 4)]::"ConversationPriority",
       'perf-subject-' || cv.g,
       CASE WHEN cv.g % 3 = 0
            THEN (SELECT e.id FROM employees e WHERE e."organizationId" = cv."organizationId" LIMIT 1)
            ELSE NULL END,
       5,
       cv."createdAt",
       cv."createdAt",
       cv."updatedAt"
FROM (
    SELECT gen_random_uuid() AS id,
           c."organizationId", c."projectId", c.id AS "customerId",
           '#' || to_char(c."createdAt", 'MMDD') ||
               lpad((row_number() OVER (ORDER BY c.id))::text, 9, '0') AS "ticketNumber",
           c."createdAt", c."updatedAt", g
    FROM customers c
    CROSS JOIN generate_series(1, 2) g
) cv;

-- ---------------------------------------------------------------------
-- 5 messages per conversation = 200 000.
-- externalMessageId is distinct per row: the uniqueness guarantee on
-- (organizationId, projectId, channel, externalMessageId) is what makes
-- inbound idempotency correct, and a seed that violated it would be testing
-- nothing.
-- ---------------------------------------------------------------------
INSERT INTO messages (id, "organizationId", "projectId", "conversationId", channel,
                      direction, "authorType", body, "externalMessageId", "deliveryStatus",
                      "sentAt", "createdAt")
SELECT m.id, m."organizationId", m."projectId", m."conversationId",
       'WHATSAPP'::"ChannelType",
       'INBOUND'::"MessageDirection",
       'CUSTOMER'::"MessageAuthorType",
       'perf-filler-body-' || m.g || ' (synthetic, not customer content)',
       m."externalMessageId",
       'DELIVERED'::"MessageDeliveryStatus",
       m."sentAt", m."sentAt"
FROM (
    SELECT gen_random_uuid() AS id,
           cv."organizationId", cv."projectId", cv.id AS "conversationId",
           cv.id::text || '-m' || g AS "externalMessageId",
           cv."createdAt" + (g || ' minutes')::interval AS "sentAt",
           g
    FROM conversations cv
    CROSS JOIN generate_series(1, 5) g
) m;

-- ---------------------------------------------------------------------
-- 2 timeline events per conversation = 80 000, append-only
-- ---------------------------------------------------------------------
INSERT INTO conversation_events (id, "organizationId", "projectId", "conversationId",
                                 type, "actorType", payload, "occurredAt")
SELECT ev.id, ev."organizationId", ev."projectId", ev."conversationId",
       'CONVERSATION_CREATED'::"ConversationEventType",
       'SYSTEM'::"MessageAuthorType",
       jsonb_build_object('synthetic', true),
       ev."occurredAt"
FROM (
    SELECT gen_random_uuid() AS id,
           cv."organizationId", cv."projectId", cv.id AS "conversationId",
           cv."createdAt" AS "occurredAt"
    FROM conversations cv
    CROSS JOIN generate_series(1, 2) g
) ev;

-- ---------------------------------------------------------------------
-- 5 notes per conversation = 200 000, project-scoped, addressed by
-- resourceType + resourceId rather than a conversation column
-- ---------------------------------------------------------------------
INSERT INTO notes (id, "organizationId", "projectId", "resourceType", "resourceId",
                   body, "authorUserId", "createdAt", "updatedAt")
SELECT n.id, n."organizationId", n."projectId", 'CONVERSATION', n."resourceId",
       'perf-note-' || n.g || ' (synthetic)',
       n."authorUserId",
       n."createdAt", n."updatedAt"
FROM (
    SELECT gen_random_uuid() AS id,
           cv."organizationId", cv."projectId", cv.id AS "resourceId",
           (SELECT u.id FROM users u WHERE u."organizationId" = cv."organizationId" LIMIT 1) AS "authorUserId",
           cv."createdAt", cv."updatedAt", g
    FROM conversations cv
    CROSS JOIN generate_series(1, 5) g
) n;

-- ---------------------------------------------------------------------
-- Retrieval: one knowledge base, one document, 2 000 chunks per tenant.
--
-- 2 000 rather than 10 000: `embedding` is vector(1536) and the HNSW index
-- from V20260814000100 is built on it, so every extra chunk costs index build
-- time and roughly 6 KB. 2 000 per tenant is enough for a prefiltered
-- nearest-neighbour search to be a real query rather than a scan, and keeps
-- the seed inside a minute on a laptop. Capacity beyond this belongs to the
-- phase that owns retrieval, not to a smoke.
-- ---------------------------------------------------------------------
INSERT INTO knowledge_bases (id, "organizationId", "projectId", name, "isDefault", "createdAt", "updatedAt")
SELECT gen_random_uuid(), t.org_id, p.project_id,
       'perf-kb-' || t.org_slug, true,
       (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC')
FROM perf_tenants t
JOIN perf_first_project p ON p.org_id = t.org_id;

INSERT INTO documents (id, "organizationId", "projectId", "knowledgeBaseId", title,
                       "storageKey", status, "chunkCount", "uploadedByUserId", "createdAt", "updatedAt")
SELECT d.id, d."organizationId", d."projectId", d."knowledgeBaseId", d.title,
       'perf/perf-' || d."organizationId"::text || '.txt',
       'READY'::"DocumentStatus",
       2000,
       d."uploadedByUserId",
       (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC')
FROM (
    SELECT gen_random_uuid() AS id,
           kb."organizationId", kb."projectId", kb.id AS "knowledgeBaseId",
           'perf-document-' || kb.name AS title,
           (SELECT u.id FROM users u WHERE u."organizationId" = kb."organizationId" LIMIT 1) AS "uploadedByUserId"
    FROM knowledge_bases kb
) d;

INSERT INTO document_chunks (id, "organizationId", "projectId", "knowledgeBaseId", "documentId",
                             "chunkIndex", content, "tokenCount", embedding, "createdAt")
SELECT gen_random_uuid(), d."organizationId", d."projectId", d."knowledgeBaseId", d.id,
       g,
       'perf-chunk-' || g || ' (synthetic)',
       32,
       ('[' || e.v || ']')::vector(1536),
       (now() AT TIME ZONE 'UTC')::timestamp(3)
FROM documents d
CROSS JOIN generate_series(0, 1999) g
-- A pseudo-embedding. random() is enough: the point is that the vector
-- column, its dimension and the HNSW index exist and are exercised, not that
-- the vectors mean anything. pgvector literals are bracket-delimited and
-- comma-separated; array_agg's brace form is not a vector literal and
-- pgvector rejects it with "Vector contents must start with [".
CROSS JOIN LATERAL (
    SELECT string_agg(((random() * 2 - 1)::real)::text, ',' ORDER BY i) AS v
    FROM generate_series(1, 1536) i
) e;

COMMIT;

-- ---------------------------------------------------------------------
-- What was actually written. A performance claim that does not state its row
-- counts is not reproducible.
--
-- This query is not decoration. An earlier revision of this script built its
-- tenant lookup table BEFORE inserting the tenants, so every join matched
-- nothing, every INSERT inserted zero rows, and psql still exited 0. A seed
-- that silently seeds nothing is worse than a seed that fails.
-- ---------------------------------------------------------------------
SELECT 'organizations' AS entity, count(*)::text AS rows FROM organizations WHERE slug LIKE 'perf-org-%'
UNION ALL SELECT 'projects',        count(*)::text FROM projects        WHERE slug LIKE 'perf-project-%'
UNION ALL SELECT 'users',           count(*)::text FROM users           WHERE email LIKE 'perf-user-%@perf.invalid'
UNION ALL SELECT 'employees',       count(*)::text FROM employees       WHERE department LIKE 'perf-dept-%'
UNION ALL SELECT 'project_memberships', count(*)::text FROM project_memberships pm JOIN users u ON u.id = pm."userId" WHERE u.email LIKE 'perf-user-%'
UNION ALL SELECT 'customers',       count(*)::text FROM customers       WHERE "displayName" LIKE 'perf-customer-%'
UNION ALL SELECT 'customer_identities', count(*)::text FROM customer_identities WHERE "externalId" LIKE 'perf-handle-%'
UNION ALL SELECT 'conversations',   count(*)::text FROM conversations   WHERE "ticketNumber" LIKE '#%'
UNION ALL SELECT 'messages',        count(*)::text FROM messages        WHERE "externalMessageId" LIKE '%-m_'
UNION ALL SELECT 'conversation_events', count(*)::text FROM conversation_events ce JOIN conversations c ON c.id = ce."conversationId" WHERE c."ticketNumber" LIKE '#%'
UNION ALL SELECT 'notes',           count(*)::text FROM notes           WHERE body LIKE 'perf-note-%'
UNION ALL SELECT 'knowledge_bases', count(*)::text FROM knowledge_bases WHERE name LIKE 'perf-kb-%'
UNION ALL SELECT 'documents',       count(*)::text FROM documents       WHERE title LIKE 'perf-document-%'
UNION ALL SELECT 'document_chunks', count(*)::text FROM document_chunks WHERE content LIKE 'perf-chunk-%'
ORDER BY 1;