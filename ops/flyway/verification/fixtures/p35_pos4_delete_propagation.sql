-- POS-4. Deleting a doctor must behave EXACTLY as it did before Phase 35:
-- the appointment row SURVIVES, "doctorId" becomes NULL, and
-- "organizationId" is NOT disturbed.
--
-- This is the behavioural claim behind the ON DELETE SET NULL (column) form in
-- V20261009110000. Two wrong choices are being ruled out:
--   * ON DELETE CASCADE (copied from V20261006110000, which legitimately
--     cascades) would DELETE the appointment.
--   * bare ON DELETE SET NULL would try to null the NOT NULL "organizationId"
--     and make the DELETE itself fail.
DO $p35_delete$
DECLARE
    before_rows bigint;
    after_rows  bigint;
    v_org uuid;
    v_doc uuid;
    v_app uuid;
BEGIN
    SELECT count(*) INTO before_rows FROM public.appointments;

    SELECT a."id", a."organizationId", d."id" INTO v_app, v_org, v_doc
      FROM public.appointments a
      JOIN public.doctors d ON d."id" = a."doctorId"
     WHERE d."name" = 'P35 doctor in tenant A'
     LIMIT 1;

    IF v_app IS NULL THEN
        RAISE EXCEPTION 'P35_DELETE_SETUP: the fixture appointment naming the tenant A doctor is absent';
    END IF;

    DELETE FROM public.doctors WHERE "id" = v_doc;

    SELECT count(*) INTO after_rows FROM public.appointments;

    IF after_rows <> before_rows THEN
        RAISE EXCEPTION
            'P35_DELETE_FAILED: appointment rows went from % to %, so deleting a doctor DELETED an '
            'appointment. That is ON DELETE CASCADE behaviour and it must never happen here.',
            before_rows, after_rows;
    END IF;

    IF (SELECT "doctorId" FROM public.appointments WHERE "id" = v_app) IS NOT NULL THEN
        RAISE EXCEPTION 'P35_DELETE_FAILED: "doctorId" was not nulled on the surviving appointment';
    END IF;

    IF (SELECT "organizationId" FROM public.appointments WHERE "id" = v_app) IS DISTINCT FROM v_org THEN
        RAISE EXCEPTION 'P35_DELETE_FAILED: "organizationId" was disturbed by deleting the doctor';
    END IF;

    RAISE NOTICE 'P35_DELETE_OK: % appointment row(s) survived, "doctorId" is NULL, "organizationId" is intact', after_rows;
END
$p35_delete$;