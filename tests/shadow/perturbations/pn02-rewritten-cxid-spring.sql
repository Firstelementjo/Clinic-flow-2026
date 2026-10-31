-- =====================================================================
-- pn02-rewritten-cxid-spring.sql
--
-- PN-02: a migrated cxId was REWRITTEN in the Spring database.
--
-- WHAT THIS PROVES. The row COUNT is unchanged -- five customers before,
-- five after. Only the identifier moved. If the comparison were over counts
-- rather than over identifier sets, this edit would be completely invisible,
-- which is exactly the failure plan.md 6.1 is written against ("keep existing
-- UUIDs, ticket numbers, customer IDs"): a count cannot tell you the
-- reference code survived the migration.
--
-- The edit also changes what `GET /customers/cx/#33000001` resolves, so CHK-1
-- turns red on the identifier-set comparison.
-- =====================================================================
UPDATE customers SET "cxId" = '#33999998'
 WHERE "id" = 'ca000001-0000-4000-8000-000000000001';