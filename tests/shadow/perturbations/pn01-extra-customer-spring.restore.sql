-- =====================================================================
-- pn01-extra-customer-spring.restore.sql
--
-- Undoes PN-01 exactly. The id is the only thing removed, so if this leaves a
-- row behind the next comparison will say so -- which is the intent: a
-- perturbation that cannot be undone is not a perturbation this gate may run.
-- =====================================================================
DELETE FROM customers WHERE "id" = 'c9ffffff-0000-4000-8000-000000000001';