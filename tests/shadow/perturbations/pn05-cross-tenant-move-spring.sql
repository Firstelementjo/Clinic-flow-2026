-- =====================================================================
-- pn05-cross-tenant-move-spring.sql
--
-- PN-05: a customer row was moved into another tenant in the Spring
--         database.
--
-- WHAT THIS PROVES. CHK-4 must turn red: the row COUNT is unchanged, the
-- primary KEY is unchanged, and the only thing that moved is the
-- organizationId. A tenant-isolation check that compares counts and primary
-- keys would call these two databases identical.
--
-- It is also the closest thing this harness has to the real cutover failure
-- it exists to catch: a migration that carries every row and every id but
-- puts one of them in the wrong tenant. That is invisible to every data
-- comparison that is not organization-aware, and it is exactly the class of
-- defect that turns into a breach after the rollback window closes.
--
-- ---------------------------------------------------------------------
-- PHASE 49. THE TARGET IS NOW DISCOVERED, AND THE REASON IS THE FINDING.
--
-- This file used to name 'ca000001-0000-4000-8000-000000000003' outright,
-- and it failed on the Spring database with:
--
--   ERROR: insert or update on table "appointments" violates foreign key
--   constraint "appointments_created_by_tenant_fkey"
--
-- That is not a harness defect and it is not a broken seed. It is the
-- Spring schema refusing, structurally, to hold the exact corruption this
-- perturbation models: the composite tenant foreign keys cascade the
-- customer's organization change into its appointments, and the appointment's
-- createdById does not exist in the new organization, so the database says no.
-- The legacy schema has no such constraint and accepts the row happily.
--
-- So the schema is the fix and the perturbation is the thing that has to
-- give. Two changes, both deliberate:
--
--   1. The target is DISCOVERED, not hard-coded. A perturbation that names a
--      row by id is fragile in a way that hides its own breakage: when the
--      id stops being perturbable the harness reports "could not be applied"
--      and nobody can tell a stale fixture from a real refusal.
--   2. The target must be a customer that NOTHING references with a composite
--      tenant foreign key, so the perturbation is applicable at all. For such
--      a row the only thing that changes is the row's own organizationId --
--      which is precisely the property CHK-4 exists to detect, so the proof
--      is preserved rather than weakened.
--
-- The consequence is recorded rather than hidden: a customer WITH dependents
-- cannot be mis-tenanted in the Spring database at all. That is the strongest
-- result this harness could have produced, and it is a fact about the schema,
-- not about the check.
-- =====================================================================

DO $pn05$
DECLARE
    target_id uuid;
    moved     integer;
BEGIN
    SELECT c."id" INTO target_id
      FROM customers c
     WHERE NOT EXISTS (SELECT 1 FROM appointments a          WHERE a."customerId" = c."id")
       AND NOT EXISTS (SELECT 1 FROM conversations k          WHERE k."customerId" = c."id")
       AND NOT EXISTS (SELECT 1 FROM customer_identities i    WHERE i."customerId" = c."id")
       AND NOT EXISTS (SELECT 1 FROM "Lead" l                WHERE l."customerId" = c."id")
     ORDER BY c."id"
     LIMIT 1;

    IF target_id IS NULL THEN
        RAISE EXCEPTION
            'PN-05 found no customer free of composite-tenant dependents, so it cannot move one '
            'into another tenant. Add a customer with no appointments, conversations, identities '
            'or leads to tests/shadow/dataset/seed-core.sql. Do NOT weaken the perturbation to '
            'succeed -- an unprovable perturbation is worse than an absent one.';
    END IF;

    UPDATE customers
       SET "organizationId" = '22222222-2222-4222-8222-222222222222'
     WHERE "id" = target_id;

    GET DIAGNOSTICS moved = ROW_COUNT;

    -- A perturbation that silently updates nothing would turn CHK-4 green and
    -- be recorded as "the detector held", which is the opposite of what
    -- happened. One row, or it did not happen.
    IF moved <> 1 THEN
        RAISE EXCEPTION 'PN-05 expected to move exactly one customer row, moved %', moved;
    END IF;

    RAISE NOTICE 'PN-05 moved customer % into the other tenant', target_id;
END
$pn05$;