# Deliberately empty JUnit results directory

A Gradle module whose `build/test-results/test/` holds no `TEST-*.xml`
has produced **no evidence**. The gate must fail on it rather than treat
"nothing failed" as "everything passed" -- that inversion is precisely how
the pre-migration integration suites reported 238 passing and 41 skipped
and were still called green.

This file exists only so the directory survives a git checkout.

Expected self-test outcome: `check-no-hidden-skips.ps1` exits non-zero with
`No TEST-*.xml files were found`.