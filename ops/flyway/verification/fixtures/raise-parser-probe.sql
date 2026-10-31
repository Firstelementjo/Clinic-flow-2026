-- Parser probe: how does plpgsql count RAISE placeholders? Each block is
-- separate so a REJECTED form does not hide the ones after it. Measured on
-- PostgreSQL 16.15 by applying this file statement-group by statement-group.
DO $probe_a$
DECLARE n bigint := 0;
BEGIN
    RAISE NOTICE 'A1 plain one placeholder %', n;
END
$probe_a$;

DO $probe_b$
DECLARE n bigint := 0;
BEGIN
    RAISE NOTICE 'B1 percent-paren (%s) here', n;
END
$probe_b$;

DO $probe_c$
DECLARE n bigint := 0;
BEGIN
    RAISE NOTICE 'C1 valid= % unique %', n, n;
END
$probe_c$;

DO $probe_d$
DECLARE n bigint := 0;
BEGIN
    RAISE NOTICE 'D1 100%% done, saw % of %', n, n;
END
$probe_d$;

DO $probe_e$
DECLARE n bigint := 0;
BEGIN
    RAISE NOTICE 'E1 refs %', array_to_string(ARRAY['a','b'], ', ');
END
$probe_e$;