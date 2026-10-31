#!/usr/bin/env pwsh
# =====================================================================
# verify-clean-bootstrap.ps1
#
# WHAT THIS PROVES
#   That the reviewed chain in ops/flyway/sql applies to an EMPTY database
#   with no manual intervention, in the shape the quality gate uses -- every
#   migration concatenated into ONE psql session -- and that the resulting
#   state matches the postBoot profile in ops/flyway/expected-state.json.
#
# WHY ONE SESSION IS THE SHAPE THAT MATTERS
#   The baseline, V20260814000000 line 56, runs
#       SELECT pg_catalog.set_config('search_path', '', false);
#   and is_local = false makes that a SESSION setting. Flyway runs every
#   migration of a boot on one connection, so search_path = '' is in force for
#   migrations 2..24 under Flyway too -- not only under a concatenated psql
#   session. rehearsal-chain.ps1 gives each file its own psql process, which
#   resets search_path between files and therefore HIDES every assertion whose
#   result depends on it. That is why the chain "passes" there and fails in the
#   gate; see docs/migration/phases/phase-30-migration-chain-bootstrap.md.
#
# WHAT IT MEASURES
#   tables, enums, CHECKs, FKs, PKs, indexes, unique indexes, unique
#   constraints, triggers, and the V1 schema fingerprint -- each compared with
#   expected-state.json's postBoot profile. A mismatch FAILS; it is never
#   reported and passed over.
#
# -FlywayDir
#   Point this at a staged copy of the chain (see
#   stage-corrected-guard-chain.ps1) to measure a candidate chain. It defaults
#   to the reviewed ops/flyway/sql, so running it with no arguments measures
#   the chain as committed.
#
# EXIT CODES: 0 clean bootstrap and expected state, 1 a count or the
# fingerprint differed, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $Database  = 'firstelement_cleancheck_boot',
    [string] $DbUser    = 'firstelement',
    [string] $ProtectedDatabase = 'firstelement',
    [string] $FlywayDir,
    [string] $EvidenceDir,

    # Do not drop the database afterwards -- for debugging a failed bootstrap.
    [switch] $Keep
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $PSCommandPath
$repoRoot  = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $scriptDir))
if (-not $FlywayDir)  { $FlywayDir  = Join-Path $repoRoot 'ops/flyway/sql' }
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repoRoot 'evidence/phase-30/clean-bootstrap' }

# --- refusals --------------------------------------------------------
# The same three the quality gate uses. This tool creates and drops databases,
# so it must never be one typo away from the development database.
if ($Database -eq $ProtectedDatabase) {
    Write-Host "REFUSING: -Database '$Database' is the protected database."; exit 2 }
if ($Database -notmatch '(?i)(check|rehearsal|gate|throwaway|scratch|tmp)') {
    # NB the -f is INSIDE the parenthesis. Written outside, PowerShell binds it to
    # Write-Host's own -ForegroundColor parameter and the refusal turns into a
    # parameter-binding error that says nothing about why it refused.
    Write-Host (("REFUSING: '{0}' does not look disposable; its name must contain one of: " +
                 "check, rehearsal, gate, throwaway, scratch, tmp.") -f $Database)
    exit 2 }
if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    Write-Host "REFUSING: '$Database' is not a plain identifier."; exit 2 }

$expected = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'ops/flyway/expected-state.json') |
            ConvertFrom-Json
$post = $expected.profiles.postBoot
$also = $post.alsoMeasured

function Invoke-Psql {
    param([string] $Sql, [string] $Db)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db `
                      -v ON_ERROR_STOP=1 -t -A -P pager=off -f - 2>&1) |
           ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $prev
    if ($LASTEXITCODE -ne 0) { throw ("psql failed on {0}:`n{1}" -f $Db, ($out -join "`n")) }
    return $out
}

$files = @(Get-ChildItem -LiteralPath $FlywayDir -Filter 'V*.sql' -File | Sort-Object Name)
if ($files.Count -eq 0) { throw "no V*.sql files in $FlywayDir" }

Write-Host "==> chain under test: $($files.Count) migration(s) from $FlywayDir"
$files | ForEach-Object { Write-Host "      $($_.Name)" }

try {
    $null = Invoke-Psql -Db $ProtectedDatabase -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$Database";
CREATE DATABASE "$Database" TEMPLATE template0;
"@

    # ONE session for the whole chain. This is the gate's shape and the shape
    # Flyway produces on a single connection, and it is the only shape under
    # which the search_path = '' the baseline installs is actually in force for
    # migrations 2..24.
    Write-Host "`n==> applying the whole chain in ONE session"
    $sb = New-Object System.Text.StringBuilder
    foreach ($f in $files) {
        $null = $sb.AppendLine(('-- ' + $f.Name))
        $null = $sb.AppendLine((Get-Content -Raw -LiteralPath $f.FullName))
    }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = ($sb.ToString() | docker exec -i $Container psql -U $DbUser -d $Database `
                                 -v ON_ERROR_STOP=1 -q -t -A -P pager=off -f - 2>&1) |
           ForEach-Object { $_.ToString() }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    foreach ($o in $out) { if ($o) { Write-Host "    $o" } }
    if ($code -ne 0) {
        throw "applying $($files.Count) migration(s) to $Database failed with exit code $code"
    }
    Write-Host "    applied $($files.Count) migration(s), exit 0"

    # --- counts ------------------------------------------------------
    # Every one of these is a claim about the DATABASE, not about the chain.
    Write-Host "`n==> measuring"
    $m = Invoke-Psql -Db $Database -Sql @"
SELECT 'tables|'            || count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relkind='r' AND c.relname <> 'flyway_schema_history';
SELECT 'enums|'             || count(DISTINCT t.typname) FROM pg_type t JOIN pg_enum e ON e.enumtypid=t.oid
  JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public';
SELECT 'checks|'            || count(*) FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND con.contype='c'
  AND c.relname <> 'flyway_schema_history';
SELECT 'foreign_keys|'      || count(*) FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND con.contype='f'
  AND c.relname <> 'flyway_schema_history';
SELECT 'primary_keys|'      || count(*) FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND con.contype='p'
  AND c.relname <> 'flyway_schema_history';
SELECT 'indexes|'           || count(*) FROM pg_indexes WHERE schemaname='public'
  AND tablename <> 'flyway_schema_history';
SELECT 'unique_indexes|'    || count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND i.indisunique
  AND c.relname <> 'flyway_schema_history';
SELECT 'unique_constraints|'|| count(*) FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND con.contype='u'
  AND c.relname <> 'flyway_schema_history';
SELECT 'triggers|'          || count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT t.tgisinternal
  AND c.relname <> 'flyway_schema_history';
SELECT 'nullable_projectId|' || count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND a.attname='projectId'
  AND NOT a.attnotnull AND a.attnum>0 AND NOT a.attisdropped AND c.relkind='r';
"@
    # Every line above is "<name>|<integer>". Anything else (a NOTICE, a psql
    # footer) is skipped rather than half-parsed, and a name that does not parse
    # is left ABSENT from $measured rather than defaulted -- so a count that
    # stops being produced is reported as a mismatch instead of silently reading
    # as "unchanged".
    $measured = @{}
    foreach ($line in $m) {
        if ($line -match '^([A-Za-z_][A-Za-z_0-9]*)\|\s*(\d+)\s*$') {
            $measured[$Matches[1]] = [int]$Matches[2]
        }
    }
    foreach ($need in @('tables','enums','checks','foreign_keys','primary_keys',
                        'indexes','unique_indexes','unique_constraints','triggers',
                        'nullable_projectId')) {
        if (-not $measured.ContainsKey($need)) {
            throw "the measurement query returned no '$need' row; refusing to report a count I did not read"
        }
    }

    # --- fingerprint -------------------------------------------------
    # Read through the existing V1 query so there is ONE fingerprint definition
    # in the repository, then measure it. V1 pins its own search_path (Phase 30),
    # so this value does not depend on how the chain was applied or on what the
    # session's search_path happened to be.
    $bundle = Join-Path $scriptDir 'run-verification.ps1'
    $bundleArgs = @{
        Container = $Container; Database = $Database; DbUser = $DbUser
        OutputDir = $EvidenceDir
        ExpectedEnumCount = 0; ExpectedTableCount = 0; ExpectedCheckCount = 0
    }
    & $bundle @bundleArgs | Out-Null
    $fpFile = Join-Path $EvidenceDir 'V1_schema_fingerprint.txt'
    if (-not (Test-Path $fpFile)) { throw "V1 produced no fingerprint at $fpFile" }
    $rows = Get-Content $fpFile | Where-Object { $_ -match '\S' }
    $md5  = [System.Security.Cryptography.MD5]::Create().
            ComputeHash([System.Text.Encoding]::UTF8.GetBytes(($rows -join "`n")))
    $fingerprint = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''

    # --- compare -----------------------------------------------------
    $checks = @(
        @{ n = 'tables';             got = $measured['tables'];             want = $post.tableCount }
        @{ n = 'enums';              got = $measured['enums'];              want = $post.enumCount }
        @{ n = 'check constraints';  got = $measured['checks'];             want = $post.checkCount }
        @{ n = 'foreign keys';       got = $measured['foreign_keys'];      want = $also.foreignKeyCount }
        @{ n = 'primary keys';       got = $measured['primary_keys'];      want = $also.primaryKeyCount }
        @{ n = 'indexes';            got = $measured['indexes'];            want = $also.indexCount }
        @{ n = 'unique indexes';     got = $measured['unique_indexes'];     want = $also.uniqueIndexCount }
        @{ n = 'unique constraints'; got = $measured['unique_constraints']; want = $also.uniqueConstraintCount }
        @{ n = 'triggers';           got = $measured['triggers'];           want = $also.triggerCount }
        @{ n = 'nullable projectId'; got = $measured['nullable_projectId']; want = $also.nullableProjectIdCount }
        @{ n = 'fingerprint';        got = $fingerprint;                    want = $post.fingerprint }
    )
    $bad = 0
    Write-Host ("{0,-20} {1,-34} {2,-34} {3}" -f 'MEASURE', 'GOT', 'EXPECTED (postBoot)', 'RESULT')
    Write-Host ('-' * 100)
    foreach ($c in $checks) {
        $ok = ([string]$c.got -eq [string]$c.want)
        if (-not $ok) { $bad++ }
        Write-Host ("{0,-20} {1,-34} {2,-34} {3}" -f $c.n, $c.got, $c.want, $(if ($ok) { 'OK' } else { 'MISMATCH' }))
    }
    Write-Host ''
    if ($bad -gt 0) {
        Write-Host "RESULT: $bad measurement(s) differ from expected-state.json postBoot." -ForegroundColor Red
        Write-Host 'A count that moves is a schema change that must be recorded, not a number to update.'
        exit 1
    }
    Write-Host "RESULT: clean bootstrap reproduced postBoot exactly. Evidence in $EvidenceDir" -ForegroundColor Green
    exit 0
}
finally {
    if (-not $Keep) {
        Write-Host "`n==> disposing $Database"
        try {
            $null = Invoke-Psql -Db $ProtectedDatabase -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$Database";
"@
        } catch { Write-Host "dispose failed: $($_.Exception.Message)" }
    }
}