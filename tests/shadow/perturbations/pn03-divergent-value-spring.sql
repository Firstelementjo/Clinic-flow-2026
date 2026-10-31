-- =====================================================================
-- pn03-divergent-value-spring.sql
--
-- PN-03: a customer display name differs in the Spring database.
--
-- WHAT THIS PROVES. CHK-2 must turn red: both stacks still return five
-- customers with the same ids, the same cxIds and the same counts, and the
-- only difference is one field's VALUE. A comparison that only checked counts,
-- key sets or ids would report parity while the two runtimes told a clinic
-- manager two different names for the same patient.
--
-- The value used is deliberately not a name from `forbiddenStrings`, so this
-- edit exercises the body comparison and not the disclosure scan.
-- =====================================================================
UPDATE customers SET "displayName" = 'Perturbation Renamed Patient'
 WHERE "id" = 'ca000001-0000-4000-8000-000000000001';