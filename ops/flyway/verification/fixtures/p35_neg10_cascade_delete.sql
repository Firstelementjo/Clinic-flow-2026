-- NEG-10. The composite constraint kept its two columns but its ON DELETE was
-- changed to CASCADE.
--
-- This is the mistake V20261006110000's own precedent invites: it used
-- ON DELETE CASCADE because document_chunks genuinely cascades, and copying
-- that clause here would DELETE an appointment whenever its doctor, customer,
-- creator or procedure is removed. The migration asserts confdeltype = 'n' so
-- the two constraints cannot quietly disagree about deletion behaviour.
ALTER TABLE public.appointments DROP CONSTRAINT appointments_procedure_tenant_fkey;
ALTER TABLE public.appointments ADD CONSTRAINT appointments_procedure_tenant_fkey
    FOREIGN KEY ("procedureId", "organizationId")
    REFERENCES public.procedures("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;