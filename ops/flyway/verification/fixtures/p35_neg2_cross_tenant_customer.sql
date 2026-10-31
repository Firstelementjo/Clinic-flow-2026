-- NEG-2. An appointment whose "customerId" belongs to a DIFFERENT tenant.
-- The patient record of one tenant attached to another tenant's appointment --
-- the disclosure the migration header describes.
INSERT INTO public.appointments
    (id, "organizationId", "projectId", title, "appointmentDate", status, "customerId", "updatedAt")
SELECT '90000000-0000-0000-0000-0000000000d2',
       ca."organizationId",
       pb."id",
       'P35 cross-tenant customer',
       now(), 'PENDING', cb."id", now()
FROM public.customers ca
CROSS JOIN public.customers cb
JOIN public.projects pb ON pb."organizationId" = cb."organizationId"
WHERE ca."displayName" = 'P35 customer tenant A'
  AND cb."displayName" = 'P35 customer tenant B';