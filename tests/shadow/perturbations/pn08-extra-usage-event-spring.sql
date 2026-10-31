-- =====================================================================
-- pn08-extra-usage-event-spring.sql
--
-- PN-08: one more usage event is APPENDED to the ledger in the Spring
--         database, and the rollup is recomputed.
--
-- ---------------------------------------------------------------------
-- WHAT CHANGED, AND WHY THE FIRST VERSION OF THIS FILE WAS WRONG
-- ---------------------------------------------------------------------
-- The first version of this perturbation tried to change the rate card's
-- markup with an UPDATE, on the reasoning that a derivation check which
-- cannot survive a changed rate is not a derivation check.
--
-- The database refused it, and the database was right:
-- `rate_card_versions`, `rate_card_version_lines`, `usage_events` and
-- `usage_rollup_snapshots` all carry `firstelement_append_only()`. The entire
-- finance ledger is immutable, which is the point of ADR 0007 Part 1 -- a
-- recomputation is a NEW generation, never an update. A perturbation that has
-- to mutate a price to prove the arithmetic is reading the price is not
-- admissible, and worse, the failed attempt was reporting itself as a product
-- defect ("the rollup did not follow the new rate") when nothing had been
-- changed.
--
-- So the mutation is ADDITIVE, which is the only kind the schema permits and
-- also the more honest test: a second cost event lands, the derived revenue
-- must move by exactly that event's cost times the markup, and the bucket's
-- sourceEventCount must rise by one. A rollup whose revenue was cached,
-- hard-coded, or computed from anything other than the events cannot follow an
-- event appended after it was first computed.
--
-- MESSAGE x 2.000000 at markup 1.500000 must add 3.000000 of revenue to the
-- MESSAGE bucket: 15.000000 -> 18.000000.
-- =====================================================================
INSERT INTO usage_events ("id", "organizationId", "projectId", "customerId", "dimension", "quantity", "unit", "sourceEventType", "sourceEventId", "sourceEntityType", "sourceEntityId", "actorUserId", "principalType", "principalName", "occurredAt", "periodStart", "periodEnd", "billingPeriodId", "currency", "costAmount", "metadata", "createdAt") VALUES
  ('d1000001-0000-4000-8000-0000000000f1', '11111111-1111-4111-8111-111111111111', 'a1a1a1a1-0000-4000-8000-000000000001', 'ca000001-0000-4000-8000-000000000001',
   'MESSAGE', 2.000000, 'message', 'MESSAGE_SENT', 'a0000001-0000-4000-8000-000000000002', 'MESSAGE', 'a0000001-0000-4000-8000-000000000002',
   '0a000001-0000-4000-8000-000000000002', 'USER', 'Shadow Agent A One', TIMESTAMP '2026-01-20 12:00:00',
   DATE '2026-01-01', DATE '2026-01-31', 'e3000001-0000-4000-8000-000000000001', 'SAR', 2.000000, '{}', '2026-01-20 12:00:00');