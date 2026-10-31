-- =====================================================================
-- pn08-extra-usage-event-spring.restore.sql
--
-- `usage_events` carries firstelement_append_only(), so the perturbation is
-- removed the only way the schema permits undoing an INSERT: by deleting the
-- row by its fixed id. A DELETE is blocked by the same trigger, which is why
-- this restore file is only ever run by an operator against a DISPOSABLE
-- shadow database -- never against anything else. The gate refuses to run if
-- the database name does not look disposable, and the database is dropped at
-- the end of the run regardless.
--
-- The rollup snapshots computed while the extra event existed stay: they are
-- append-only rows and are part of the evidence that a recomputation created a
-- NEW generation. They are identified by generation 2 in the PN-08 artefact.
-- =====================================================================
ALTER TABLE usage_events DISABLE TRIGGER usage_events_append_only;

DELETE FROM usage_events WHERE "id" = 'd1000001-0000-4000-8000-0000000000f1';

ALTER TABLE usage_events ENABLE TRIGGER usage_events_append_only;