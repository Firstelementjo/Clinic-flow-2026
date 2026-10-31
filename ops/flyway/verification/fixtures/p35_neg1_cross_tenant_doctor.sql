-- NEG-1. An appointment whose "doctorId" belongs to a DIFFERENT tenant.
-- The appointment's organizationId is tenant A's (taken from tenant A's
-- doctor) while the doctorId names tenant B's doctor. This is the defect
-- V20261009110000 exists to prevent, and it is INSERTABLE today because
-- appointments_doctorId_fkey only checks that the row exists.
--
-- The projectId comes from tenant B so the row is otherwise ordinary.
INSERT INTO public.appointments
    (id, "organizationId", "projectId", title, "appointmentDate", status, "doctorId", "updatedAt")
SELECT '90000000-0000-0000-0000-0000000000d1',
       da."organizationId",
       pb."id",
       'P35 cross-tenant doctor',
       now(), 'PENDING', db."id", now()
FROM public.doctors da
CROSS JOIN public.doctors db
JOIN public.projects pb ON pb."organizationId" = db."organizationId"
WHERE da."name" = 'P35 doctor in tenant A'
  AND db."name" = 'P35 doctor in tenant B';