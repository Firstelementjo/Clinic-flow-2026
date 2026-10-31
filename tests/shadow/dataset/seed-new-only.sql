-- =====================================================================
-- seed-new-only.sql
--
-- APPLIED TO shadow_check_spring ONLY, after that runtime has migrated the
-- database from empty through its own Flyway chain.
--
-- These tables do not exist in the legacy schema and no legacy code can read
-- them. That is not a reason to skip them: ADR 0007 Part 1 says a usage event
-- stores COST and a versioned rate card converts it into REVENUE, and the
-- Phase-12 gate names "finance rollups". So the finance comparison is made
-- against the SOURCE ROWS of this file plus the rate card it names, which is
-- the only legitimate reference available -- there is no legacy figure to
-- compare with, and inventing one would prove only that the invention was
-- consistent.
--
-- WHY NOT SEEDED IN THE LEGACY DATABASE: the tables do not exist there. An
-- insert would fail the whole chain and, worse, a harness that quietly
-- skipped half its dataset would report a parity it never measured.
--
-- NOTHING IS REAL. Every id, date and amount is a literal chosen so that the
-- derived revenue is arithmetically checkable by hand AND by SQL, independently
-- of the application:
--
--   MESSAGE      10.000000 SAR x 1.500000 = 15.000000
--   AI_REQUEST    4.000000 SAR x 1.500000 =  6.000000
--   WORKFLOW_RUN  2.000000 SAR x 1.500000 =  3.000000
--   STORAGE       1.500000 SAR x 1.500000 =  2.250000
--
--   priced cost total 17.500000  revenue 26.250000  margin 8.750000
--
-- ONE EVENT IS DELIBERATELY UNPRICED. Its dimension has no line on the rate
-- card version, so it must appear as unpriced, distinctly from zero (ADR 0007
-- rule 5). A harness that only checked the totals would not notice if unpriced
-- usage were silently billed at zero.
-- =====================================================================

BEGIN;

-- An ACTIVE rate card owned by finance configuration, not by engineering.
INSERT INTO rate_cards ("id", "organizationId", "projectId", "key", "nameAr", "nameEn", "currency", "status", "createdByUserId", "version", "createdAt", "updatedAt") VALUES
  ('e0000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', NULL,
   'shadow-standard', 'بطاقة الظل القياسية', 'Shadow Standard Card', 'SAR', 'ACTIVE',
   '0a000001-0000-4000-8000-000000000001', 1, '2026-01-06 10:00:00', '2026-01-06 10:00:00');

-- Version 1, effective from before the usage, ACTIVE and PUBLISHED: a DRAFT
-- version prices nothing, and the comparison would then be measuring the
-- difference between "priced" and "no rate card at all".
INSERT INTO rate_card_versions ("id", "organizationId", "rateCardId", "versionNumber", "currency", "markupRate", "effectiveFrom", "status", "changeNote", "publishedAt", "publishedByUserId", "createdByUserId", "createdAt") VALUES
  ('e1000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'e0000001-0000-4000-8000-000000000001', 1, 'SAR', 1.500000, '2026-01-01', 'ACTIVE',
   'Initial shadow card', '2026-01-06 10:05:00', '0a000001-0000-4000-8000-000000000001', '0a000001-0000-4000-8000-000000000001', '2026-01-06 10:05:00');

-- Per-line cost overrides. MESSAGE / AI_REQUEST / WORKFLOW_RUN / STORAGE are
-- priced. DOCUMENT_CHUNK is deliberately ABSENT, which is what makes the last
-- usage event below unpriced rather than free.
--
-- THE `unit` COLUMN IS NOT COSMETIC. `RateCardResolver` looks a line up by
-- (`dimension`, `dimension.canonicalUnit()`) -- the dimension's OWN unit, not
-- whatever unit the usage event happened to carry. The first version of this
-- seed wrote 'gb-day' for STORAGE, the runtime reported the bucket as
-- UNPRICED, and the obvious reading was "the rollup failed to price a
-- dimension it had a line for". It had not: STORAGE's canonical unit is
-- `byte_day`. The product was right and the fixture was wrong, which is worth
-- recording because the mistake a reader would make here is to file a defect
-- against the cutover candidate on the strength of a bad fixture.
INSERT INTO rate_card_version_lines ("id", "organizationId", "rateCardVersionId", "dimension", "unit", "costAmount", "currency", "createdAt") VALUES
  ('e2000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'e1000001-0000-4000-8000-000000000001', 'MESSAGE',      'message',  1.000000, 'SAR', '2026-01-06 10:06:00'),
  ('e2000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'e1000001-0000-4000-8000-000000000001', 'AI_REQUEST',   'request',  1.000000, 'SAR', '2026-01-06 10:06:10'),
  ('e2000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'e1000001-0000-4000-8000-000000000001', 'WORKFLOW_RUN', 'run',      1.000000, 'SAR', '2026-01-06 10:06:20'),
  ('e2000001-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'e1000001-0000-4000-8000-000000000001', 'STORAGE',      'byte_day', 1.000000, 'SAR', '2026-01-06 10:06:30');

UPDATE rate_cards SET "currentVersionId" = 'e1000001-0000-4000-8000-000000000001',
                      "latestVersionNumber" = 1
 WHERE "id" = 'e0000001-0000-4000-8000-000000000001';

-- A January 2026 period, so `periodStart`/`periodEnd` on each event point at a
-- row that exists rather than at nothing. `finance_periods` has no
-- `createdByUserId` and no `projectId`; the column list below is the table's
-- real NOT NULL set, which is why this insert is written out longhand instead
-- of relying on defaults.
INSERT INTO finance_periods ("id", "organizationId", "key", "nameAr", "nameEn", "periodStart", "periodEnd", "currency", "status", "version", "createdAt", "updatedAt")
SELECT 'e3000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
       '2026-01', 'يناير 2026', 'January 2026', DATE '2026-01-01', DATE '2026-01-31', 'SAR', 'OPEN', 0,
       '2026-01-01 00:00:00', '2026-01-01 00:00:00'
 WHERE NOT EXISTS (SELECT 1 FROM finance_periods WHERE "id" = 'e3000001-0000-4000-8000-000000000001');

-- The usage events themselves. Each names the immutable source row that
-- produced it, because `usage_events_source_entity_check` makes that
-- unrepresentable -- an amount that cannot name its source is a bug, not a
-- rounding artefact (ADR 0007 rule 1).
INSERT INTO usage_events ("id", "organizationId", "projectId", "customerId", "dimension", "quantity", "unit", "sourceEventType", "sourceEventId", "sourceEntityType", "sourceEntityId", "actorUserId", "principalType", "principalName", "occurredAt", "periodStart", "periodEnd", "billingPeriodId", "currency", "costAmount", "metadata", "createdAt") VALUES
  ('d1000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000001',
   'MESSAGE', 10.000000, 'message', 'MESSAGE_SENT', 'a0000001-0000-4000-8000-000000000001', 'MESSAGE', 'a0000001-0000-4000-8000-000000000001',
   '0a000001-0000-4000-8000-000000000002', 'USER', 'Shadow Agent A One', TIMESTAMP '2026-01-20 11:30:00',
   DATE '2026-01-01', DATE '2026-01-31', 'e3000001-0000-4000-8000-000000000001', 'SAR', 10.000000, '{}', '2026-01-20 11:30:00'),

  ('d1000001-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000001',
   'AI_REQUEST', 4.000000, 'request', 'AI_RUN_STARTED', 'f0000001-0000-4000-8000-000000000005', 'CONVERSATION', 'f0000001-0000-4000-8000-000000000005',
   '0a000001-0000-4000-8000-000000000002', 'AI_AGENT', 'Shadow Agent A One', TIMESTAMP '2026-01-20 16:00:00',
   DATE '2026-01-01', DATE '2026-01-31', 'e3000001-0000-4000-8000-000000000001', 'SAR', 4.000000, '{}', '2026-01-20 16:00:00'),

  ('d1000001-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', NULL,
   'WORKFLOW_RUN', 2.000000, 'run', 'WORKFLOW_EXECUTED', 'f0000001-0000-4000-8000-000000000001', 'CONVERSATION', 'f0000001-0000-4000-8000-000000000001',
   NULL, 'JOB', 'shadow-rollup-job', TIMESTAMP '2026-01-21 09:00:00',
   DATE '2026-01-01', DATE '2026-01-31', 'e3000001-0000-4000-8000-000000000001', 'SAR', 2.000000, '{}', '2026-01-21 09:00:00'),

  ('d1000001-0000-4000-8000-000000000004', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', NULL,
   'STORAGE', 1.500000, 'byte_day', 'STORAGE_METERED', 'a0000001-0000-4000-8000-000000000001', 'MESSAGE', 'a0000001-0000-4000-8000-000000000001',
   NULL, 'JOB', 'shadow-storage-meter', TIMESTAMP '2026-01-21 09:05:00',
   DATE '2026-01-01', DATE '2026-01-31', 'e3000001-0000-4000-8000-000000000001', 'SAR', 1.500000, '{}', '2026-01-21 09:05:00'),

  -- UNPRICED: DOCUMENT_CHUNK has no line on the rate card version. This row
  -- has a real cost and must appear as unpriced, not as zero.
  ('d1000001-0000-4000-8000-000000000005', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', NULL,
   'DOCUMENT_CHUNK', 3.000000, 'chunk', 'CHUNK_EMBEDDED', 'a0000001-0000-4000-8000-000000000001', 'MESSAGE', 'a0000001-0000-4000-8000-000000000001',
   NULL, 'JOB', 'shadow-embedding-job', TIMESTAMP '2026-01-21 09:10:00',
   DATE '2026-01-01', DATE '2026-01-31', 'e3000001-0000-4000-8000-000000000001', 'SAR', 3.000000, '{}', '2026-01-21 09:10:00');

COMMIT;