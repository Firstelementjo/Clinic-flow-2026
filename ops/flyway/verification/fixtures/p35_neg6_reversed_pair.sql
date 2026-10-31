-- NEG-6. The composite constraint REPLACED with the REVERSED column pair.
--
--     FOREIGN KEY ("doctorId", "organizationId") -> doctors("id", "organizationId")
-- becomes
--     FOREIGN KEY ("organizationId", "doctorId") -> doctors("id", "organizationId")
--
-- which binds organizationId -> doctors.id and doctorId -> doctors.organizationId.
-- A text assertion like '%("organizationId", "doctorId") REFERENCES doctors%' would
-- NOT catch this -- the reversed form is exactly what such a pattern searches
-- for. The migration's post-condition resolves conkey/confkey through
-- pg_attribute and compares them position by position, which does.
ALTER TABLE public.appointments DROP CONSTRAINT appointments_doctor_tenant_fkey;
ALTER TABLE public.appointments ADD CONSTRAINT appointments_doctor_tenant_fkey
    FOREIGN KEY ("organizationId", "doctorId")
    REFERENCES public.doctors("id", "organizationId")
    ON UPDATE CASCADE ON DELETE SET NULL ("organizationId");