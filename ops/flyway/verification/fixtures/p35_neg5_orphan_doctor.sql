-- NEG-5. An ORPHAN "doctorId": the appointment names a uuid that is not in
-- doctors at all.
--
-- appointments_doctorId_fkey is DROPPED for this one column, because that
-- constraint is exactly what makes an orphan uninsertable. The point of this
-- control is the OTHER failure mode ADR-0009 §5 names: a row can exist that
-- the FK would refuse (a database restored from a dump taken with the
-- constraint disabled never re-validates), and the migration must detect it
-- with a clear message instead of failing later with an opaque ALTER error.
-- The constraint name is QUOTED. PostgreSQL folds an unquoted
-- appointments_doctorId_fkey to appointments_doctorid_fkey, which matches
-- nothing and fails with 'constraint "appointments_doctorid_fkey" of relation
-- "appointments" does not exist' -- the very orphan this control needs to seed.
ALTER TABLE public.appointments DROP CONSTRAINT "appointments_doctorId_fkey";

INSERT INTO public.appointments
    (id, "organizationId", "projectId", title, "appointmentDate", status, "doctorId", "updatedAt")
SELECT '90000000-0000-0000-0000-0000000000d5',
       d."organizationId",
       p."id",
       'P35 orphan doctor',
       now(), 'PENDING', '90000000-0000-0000-0000-00000000dead', now()
FROM public.doctors d
JOIN public.projects p ON p."organizationId" = d."organizationId"
WHERE d."name" = 'P35 doctor in tenant A'
LIMIT 1;