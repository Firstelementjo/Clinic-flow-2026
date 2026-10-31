-- Fixture for check-raise-placeholders.ps1.
--
-- The EXPECTED verdicts below are the ones MEASURED on PostgreSQL 16.15 by
-- applying each block separately (see raise-parser-probe.sql). They corrected
-- an earlier draft of the checker, which assumed a "%" followed by punctuation
-- was not a placeholder -- PostgreSQL accepts '%(' fine, renders '(%s)' as
-- '(0s)', and consumes exactly one argument.
--
-- Lines 13, 17 and 21 are the three the checker must call BAD: each is a real
-- placeholder/argument mismatch that makes plpgsql refuse to COMPILE the
-- block, which aborts the entire migration before any of its logic runs.
DO $$
DECLARE n bigint := 0; s text := 'x'; a text[] := ARRAY['p','q'];
BEGIN
    -- OK (1 placeholder, 1 argument)
    RAISE NOTICE 'one %', n;

    -- OK, and this is the case that corrected the checker: "%(" -- the "(" is
    -- literal, "%s" is a placeholder. One argument, as supplied.
    RAISE NOTICE 'column (%s) is missing', s;

    -- OK: "%%" is a literal percent and consumes nothing, so this has exactly
    -- two placeholders and two arguments.
    RAISE NOTICE '100%% done, saw % of %', n, n;

    -- BAD: 2 placeholders, 1 argument -> "too few parameters specified".
    RAISE NOTICE 'only one % here', n, n;

    -- BAD: 1 placeholder, 2 arguments -> "too many parameters specified".
    RAISE NOTICE 'one % and %', n, n;

    -- OK: the comma is inside a quoted literal, not an argument separator.
    RAISE NOTICE 'names % and %', s, s;

    -- OK: a comma inside a parenthesised call is not a separator either.
    RAISE NOTICE 'refs %', array_to_string(a, ', ');
END
$$;