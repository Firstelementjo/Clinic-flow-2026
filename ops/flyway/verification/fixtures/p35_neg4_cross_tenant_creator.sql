-- NEG-4. An appointment whose "createdById" names a user of a DIFFERENT tenant.
-- An appointment attributed to another organisation's employee.
INSERT INTO public.appointments
    (id, "organizationId", "projectId", title, "appointmentDate", status, "createdById", "updatedAt")
SELECT '90000000-0000-0000-0000-0000000000d4',
       ua."organizationId",
       pb."id",
       'P35 cross-tenant creator',
       now(), 'PENDING', ub."id", now()
FROM public.users ua
CROSS JOIN public.users ub
JOIN public.projects pb ON pb."organizationId" = ub."organizationId"
WHERE ua."email" = 'p35.a@tenant-a.example'
  AND ub."email" = 'p35.b@tenant-b.example';