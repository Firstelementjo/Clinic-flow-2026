-- POS-3. Tenant A's appointment tries to attach TENANT B's doctor.
--
-- Under the pre-Phase-35 constraints this SUCCEEDS, because
-- appointments_doctorId_fkey only checks that the doctor row exists. With
-- appointments_doctor_tenant_fkey it must be REFUSED.
--
-- The organizationId and projectId come from tenant A, so the row is internally
-- ordinary and the doctorId is the single mis-scoped column -- which is exactly
-- the defect, and exactly what the composite pair binds.
--
-- A NOTE ON AN EARLIER DRAFT OF THIS FIXTURE, because it produced a control
-- that could never go red:
-- it joined projects to the DOCTOR's organization, which handed tenant A's
-- doctor tenant A's project, so the row it inserted was same-tenant, the insert
-- SUCCEEDED, and the control reported "constraint did not refuse a cross-tenant
-- write" while having proved nothing. The join below deliberately takes BOTH the
-- organizationId and the projectId from tenant A and only the doctorId from
-- tenant B. A negative control that cannot fail is worse than none.
INSERT INTO public.appointments
    (id, "organizationId", "projectId", title, "appointmentDate", status, "doctorId", "updatedAt")
SELECT '90000000-0000-0000-0000-000000000010',
       pa."organizationId",
       pa."id",
       'tenant A names tenant B doctor',
       now(), 'PENDING', db."id", now()
FROM public.projects pa
CROSS JOIN public.doctors db
WHERE pa."name" = 'P35 project A'
  AND db."name" = 'P35 doctor in tenant B';