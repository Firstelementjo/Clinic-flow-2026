-- NEG-7. The FK target index made PARTIAL.
--
-- A partial unique index does not cover every referenced row, so the constraint
-- is satisfied by a lookup that can MISS: a doctor excluded by the predicate is
-- not protected by the key at all, and the FK silently accepts a reference to a
-- row the index knows nothing about.
--
-- THE ORDER IS FORCED BY PostgreSQL, and finding that out is the point. Two
-- orders were tried against PostgreSQL 16.15 and both are refused:
--
--   (a) DROP CONSTRAINT; DROP INDEX; CREATE UNIQUE INDEX ... WHERE ...;
--       ADD CONSTRAINT  ->
--       ERROR: there is no unique constraint matching given keys for
--              referenced table "doctors"
--       PostgreSQL will not attach a foreign key to a PARTIAL unique index at
--       all. So this order cannot PRODUCE the bad state; it can only fail.
--
--   (b) CREATE the full index, ADD the constraint, then swap the index for a
--       partial one under the constraint ->
--       ERROR: cannot drop index "doctors_id_organizationId_key"
--              because other objects depend on it
--       The index backs the constraint, so it cannot be replaced in place.
--
-- The only way to reach the state is to build the partial index under a
-- DIFFERENT NAME while the real one still backs the constraint, which is
-- precisely what this fixture does. The migration's post-condition iterates
-- over all FOUR target indexes -- including the two it did not create -- so a
-- partial index reached by any route must be caught, not only the one its own
-- CREATE UNIQUE INDEX would have produced.
-- Rename the REAL index aside first, then build the partial one under the name
-- the post-condition looks up. The end state is therefore: a composite FK in
-- exactly the right shape, whose declared target is a PARTIAL unique index.
ALTER INDEX public."doctors_id_organizationId_key" RENAME TO "doctors_id_organizationId_key_full";

CREATE UNIQUE INDEX "doctors_id_organizationId_key"
    ON public.doctors USING btree ("id", "organizationId")
    WHERE "specialty" IS NOT NULL;