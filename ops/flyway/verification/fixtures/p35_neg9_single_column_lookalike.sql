-- NEG-9. A composite constraint REPLACED by a SINGLE-column one of the same
-- name and intent.
--
--     FOREIGN KEY ("doctorId", "organizationId") -> doctors("id","organizationId")
-- becomes
--     FOREIGN KEY ("doctorId") -> doctors("id")
--
-- i.e. exactly the shape the baseline shipped, which is the defect this batch
-- closes. An assertion that only checked "does appointments_doctor_tenant_fkey
-- exist" would pass; the migration resolves conkey and requires TWO columns.
--
-- The same trap, applied to the ON DELETE clause rather than the column list,
-- is NEG-10.
ALTER TABLE public.appointments DROP CONSTRAINT appointments_customer_tenant_fkey;
ALTER TABLE public.appointments ADD CONSTRAINT appointments_customer_tenant_fkey
    FOREIGN KEY ("customerId")
    REFERENCES public.customers("id")
    ON UPDATE CASCADE ON DELETE SET NULL;