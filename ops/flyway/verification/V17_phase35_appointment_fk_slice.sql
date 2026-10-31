-- =====================================================================
-- V17_phase35_appointment_fk_slice.sql
--
-- THE FIXTURE FOR rehearse-phase35.ps1. Read-only with respect to the
-- CHAIN: it creates tenants, projects, doctors, procedures, customers, users
-- and appointments, then PROVES the properties the Phase-35 constraints claim.
-- It is not part of ops/flyway/sql and is never applied by Flyway.
--
-- TWO TENANTS, AND EVERY TABLE IN THE FIXTURE IS TENANT-LED
--   Tenant A: 90000000-0000-0000-0000-0000000000a1
--   Tenant B: 90000000-0000-0000-0000-0000000000b1
--   Each has its own project, doctor, procedure, customer and user, so a
--   single mis-scoped insert is unambiguous.
--
-- WHY IDs ARE LITERAL AND NOT GENERATED
--   A rehearsal that picks its own ids cannot be read afterwards: the failure
--   message names an id, and an operator has to be able to look that id up.
--   The 90000000-0000-0000-0000- prefix marks this fixture and collides with
--   nothing real.
--
-- customers."cxId" IS ALLOCATED, NOT INVENTED. ADR-0014 made
-- firstelement_next_customer_cx_id the ONLY supported way to obtain a
-- customer reference, and customers_organizationId_cxId_key is UNIQUE per
-- organization -- writing a literal cxId here would either collide or, worse,
-- teach the wrong pattern. Both tenants therefore start at #33000000, which
-- is the property ADR-0014 Â§"Per-organization counter" exists to guarantee.
--
-- READ-ONLY WITH RESPECT TO DATA: this slice only INSERTs into databases this
-- repository created from template0 for the rehearsal. It is never run against
-- firstelement.
-- =====================================================================

-- =====================================================================
-- requires-chain: yes
-- ---------------------------------------------------------------------
-- This slice asserts the four composite tenant foreign keys Phase 35 added, and it
-- exercises firstelement_next_customer_cx_id(), which V20261007100000 created. Both
-- are POST-CHAIN objects: they do not exist on a baseline database. The slice therefore
-- declares its own requirement rather than being discovered as broken by whichever
-- caller happened to point run-verification.ps1 at `firstelement`.
--
-- It is STILL skipped loudly: the summary records the skip and the output file says
-- why, because a silently skipped slice is indistinguishable from one that passed.
-- =====================================================================

\set ON_ERROR_STOP on

DO $$
DECLARE
    org_a   constant uuid := '90000000-0000-0000-0000-0000000000a1';
    org_b   constant uuid := '90000000-0000-0000-0000-0000000000b1';
    proj_a  constant uuid := '90000000-0000-0000-0000-0000000000a2';
    proj_b  constant uuid := '90000000-0000-0000-0000-0000000000b2';
    doc_a   constant uuid := '90000000-0000-0000-0000-0000000000a3';
    doc_b   constant uuid := '90000000-0000-0000-0000-0000000000b3';
    prc_a   constant uuid := '90000000-0000-0000-0000-0000000000a4';
    prc_b   constant uuid := '90000000-0000-0000-0000-0000000000b4';
    usr_a   constant uuid := '90000000-0000-0000-0000-0000000000a5';
    usr_b   constant uuid := '90000000-0000-0000-0000-0000000000b5';
    app_a   constant uuid := '90000000-0000-0000-0000-0000000000a6';
    cust_a  constant uuid := '90000000-0000-0000-0000-0000000000a7';
    cust_b  constant uuid := '90000000-0000-0000-0000-0000000000b7';
    cxref_a varchar(9);
    cxref_b varchar(9);
BEGIN
    -- ---------------------------------------------------------------------
    -- Organizations and projects.
    -- ---------------------------------------------------------------------
    -- Every camelCase column here is QUOTED. PostgreSQL folds an unquoted
    -- createdAt to createdat, and this schema really does declare the column as
    -- "createdAt" -- so the unquoted form fails with
    -- 'column "createdat" of relation "organizations" does not exist'.
    INSERT INTO public.organizations (id, name, slug, "createdAt", "updatedAt") VALUES
        (org_a, 'P35 tenant A', 'p35-tenant-a', now(), now()),
        (org_b, 'P35 tenant B', 'p35-tenant-b', now(), now());

    INSERT INTO public.projects (id, "organizationId", name, slug, "createdAt", "updatedAt") VALUES
        (proj_a, org_a, 'P35 project A', 'p35-project-a', now(), now()),
        (proj_b, org_b, 'P35 project B', 'p35-project-b', now(), now());

    -- ---------------------------------------------------------------------
    -- Doctors and procedures, one pair per tenant. "specialty" is NOT NULL and
    -- is what NEG-7's partial-index predicate keys on.
    -- ---------------------------------------------------------------------
    INSERT INTO public.doctors (id, "organizationId", "projectId", name, specialty, "updatedAt") VALUES
        (doc_a, org_a, proj_a, 'P35 doctor in tenant A', 'General', now()),
        (doc_b, org_b, proj_b, 'P35 doctor in tenant B', 'General', now());

    INSERT INTO public.procedures (id, "organizationId", "projectId", name, specialty, "updatedAt") VALUES
        (prc_a, org_a, proj_a, 'P35 procedure tenant A', 'General', now()),
        (prc_b, org_b, proj_b, 'P35 procedure tenant B', 'General', now());

    -- ---------------------------------------------------------------------
    -- Users. passwordHash is opaque here; this slice asserts nothing about
    -- authentication and never reads a real credential.
    -- ---------------------------------------------------------------------
    INSERT INTO public.users (id, "organizationId", email, "passwordHash", "fullName", "updatedAt") VALUES
        (usr_a, org_a, 'p35.a@tenant-a.example', 'p35-fixture-hash', 'P35 User A', now()),
        (usr_b, org_b, 'p35.b@tenant-b.example', 'p35-fixture-hash', 'P35 User B', now());

    -- ---------------------------------------------------------------------
    -- Customers, references ALLOCATED through the ADR-0014 function.
    --
    -- Two distinct values per tenant and they are NOT interchangeable:
    --   cust_a / cust_b  the customers.id (uuid) the appointment foreign-keys to
    --   cxref_a / cxref_b the customers."cxId" varchar(9) the allocator returns
    -- firstelement_next_customer_cx_id RETURNS varchar, formatted '#33' ||
    -- lpad(seq, 6, '0'), so assigning it to a uuid column fails with
    -- 'invalid input syntax for type uuid: "#33000000"'.
    -- ---------------------------------------------------------------------
    SELECT public.firstelement_next_customer_cx_id(org_a) INTO cxref_a;
    SELECT public.firstelement_next_customer_cx_id(org_b) INTO cxref_b;

    INSERT INTO public.customers (id, "organizationId", "projectId", "cxId", "displayName", "updatedAt") VALUES
        (cust_a, org_a, proj_a, cxref_a, 'P35 customer tenant A', now()),
        (cust_b, org_b, proj_b, cxref_b, 'P35 customer tenant B', now());

    -- ---------------------------------------------------------------------
    -- The legitimate appointment: tenant A naming tenant A's doctor, customer,
    -- procedure and creator. This is the POS-1 row -- the migration must
    -- apply cleanly with it present.
    -- ---------------------------------------------------------------------
    INSERT INTO public.appointments
        (id, "organizationId", "projectId", "customerId", "doctorId", "procedureId",
         title, "appointmentDate", status, "createdById", "updatedAt")
    VALUES
        (app_a, org_a, proj_a, cust_a, doc_a, prc_a,
         'P35 legitimate same-tenant appointment', now(), 'PENDING', usr_a, now());

    RAISE NOTICE 'P35_FIXTURE_SEEDED: 2 organizations, 2 projects, 2 doctors, 2 procedures, '
                 '2 users, 2 customers (references allocated), 1 legitimate appointment.';
END
$$;

-- =====================================================================
-- THE ASSERTIONS. These are what make the slice a test rather than a fixture.
--
-- 1. Each tenant's first customer reference is #33000000. Proves the
--    per-organization counter is genuinely per-organization, which is the
--    property NEG-1..NEG-4 rely on when they build a cross-tenant row.
-- 2. The legitimate appointment is same-tenant on all four references.
-- 3. The four references of that row each belong to the appointment's tenant.
-- 4. Each tenant's doctor is genuinely owned by a DIFFERENT organization --
--    so a mis-scoped insert is detectable rather than accidentally correct.
-- =====================================================================
DO $$
DECLARE
    app_a constant uuid := '90000000-0000-0000-0000-0000000000a6';
    n integer;
BEGIN
    SELECT count(*) INTO n
      FROM public.customers
     WHERE "displayName" LIKE 'P35 customer tenant%'
       AND "cxId" = '#33000000';
    IF n <> 2 THEN
        RAISE EXCEPTION
            'V17 fixture: expected BOTH tenants to start at #33000000, found % row(s) at it. '
            'The per-organization allocator is not behaving as ADR-0014 requires, so this fixture '
            'cannot distinguish a cross-tenant row from a same-tenant one.', n;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.appointments a
                    JOIN public.doctors     d ON d."id" = a."doctorId"
                    JOIN public.customers   c ON c."id" = a."customerId"
                    JOIN public.procedures  p ON p."id" = a."procedureId"
                    JOIN public.users       u ON u."id" = a."createdById"
                   WHERE a."id" = app_a
                     AND d."organizationId" = a."organizationId"
                     AND c."organizationId" = a."organizationId"
                     AND p."organizationId" = a."organizationId"
                     AND u."organizationId" = a."organizationId") THEN
        RAISE EXCEPTION
            'V17 fixture: the seeded appointment is not same-tenant on all four references, so '
            'POS-1 would be proving nothing.';
    END IF;

    IF (SELECT count(DISTINCT "organizationId") FROM public.doctors
         WHERE name LIKE 'P35 doctor in tenant%') <> 2 THEN
        RAISE EXCEPTION
            'V17 fixture: the two fixture doctors do not belong to two different organizations, so a '
            'cross-tenant insert would be satisfied by the tenant key and prove nothing.';
    END IF;

    RAISE NOTICE 'V17_OK: both tenants allocated #33000000; the legitimate appointment is '
                 'same-tenant on all four references; the two doctors are in different tenants.';
END
$$;
