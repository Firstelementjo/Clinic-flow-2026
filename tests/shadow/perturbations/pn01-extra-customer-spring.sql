-- =====================================================================
-- pn01-extra-customer-spring.sql
--
-- PN-01: an extra customer row exists ONLY in the Spring database.
--
-- WHAT THIS PROVES. CHK-1 (row counts and identifier sets read through both
-- runtimes) and CHK-4 (row counts and identifier sets read straight out of
-- both databases) must both turn red.
--
-- WHAT WOULD MAKE THE CHECKS FAIL TO NOTICE: if either side counted
-- something that cannot differ -- a constant, a cached total, a projection
-- with a hard-coded length -- this edit would be invisible. That is the whole
-- reason the edit exists.
--
-- The id is fixed and outside the seeded range, so the restore below can
-- delete exactly one row and the check that the restore worked is that the
-- row count returns to 5.
-- =====================================================================
INSERT INTO customers ("id", "organizationId", "projectId", "cxId", "displayName", "locale", "tags", "externalIds", "metadata", "firstSeenAt", "lastSeenAt", "createdAt", "updatedAt")
VALUES ('c9ffffff-0000-4000-8000-000000000001',
        '11111111-1111-4111-8111-111111111111',
        'a1a1a1a1-0000-4000-8000-000000000001',
        '#33999999',
        'Perturbation Row',
        'ar', ARRAY[]::text[], '{}', '{}',
        '2026-01-25 09:00:00', '2026-01-25 09:00:00', '2026-01-25 09:00:00', '2026-01-25 09:00:00');