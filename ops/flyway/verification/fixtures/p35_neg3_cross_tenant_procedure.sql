-- NEG-3. An appointment whose "procedureId" belongs to a DIFFERENT tenant.
INSERT INTO public.appointments
    (id, "organizationId", "projectId", title, "appointmentDate", status, "procedureId", "updatedAt")
SELECT '90000000-0000-0000-0000-0000000000d3',
       pa."organizationId",
       pb."id",
       'P35 cross-tenant procedure',
       now(), 'PENDING', pb2."id", now()
FROM public.procedures pa
CROSS JOIN public.procedures pb2
JOIN public.projects pb ON pb."organizationId" = pb2."organizationId"
WHERE pa."name" = 'P35 procedure tenant A'
  AND pb2."name" = 'P35 procedure tenant B';