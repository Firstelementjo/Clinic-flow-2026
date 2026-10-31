#!/usr/bin/env pwsh
# =====================================================================
# rehearsal-chain.ps1
#
#   THIS IS NOW THE SECOND SHAPE, NOT THE CHAIN REHEARSAL.
#
#   The chain rehearsal is verify-clean-bootstrap.ps1, in this directory. Read
#   this section before trusting a green run from this script.
#
#   WHY THE SHAPE CHANGED (Phase 30)
#   The baseline (V20260814000000 line 56) runs
#       SELECT pg_catalog.set_config('search_path', '', false);
#   and is_local = false makes that a SESSION setting, so it lasts for the rest
#   of the connection. Flyway runs every migration of a boot on ONE connection,
#   which means migrations 2..24 execute under an empty search_path under Flyway.
#
#   One psql invocation per FILE -- which is what this script does -- gives each
#   migration a fresh process and therefore a fresh search_path. That is the
#   right shape for TRANSACTIONS, which is why this script exists: a failure
#   names exactly one migration version. It is the wrong shape for SESSION
#   STATE, and it hid five search_path-dependent guards for eleven phases:
#   V20261006110000, V20261006120000, V20261006140000 (twice) and V20261007100000
#   each asserted a REFERENCES clause with an UNQUALIFIED table name, and would
#   always fail under the search_path the chain actually runs in while passing
#   here every time. See
#   docs/migration/phases/phase-30-migration-chain-bootstrap.md.
#
#   KEEP RUNNING BOTH. This shape is still the only one that exercises a
#   per-migration transaction boundary, which the one-session shape cannot.
#
# Applies the WHOLE reviewed chain (ops/flyway/sql/V*.sql, in numeric version
# order) to a THROWAWAY database inside the dev container, then optionally runs
# the verification bundle against it.
#
# It exists so the psql rehearsal and the Flyway rehearsal exercise the same
# files in the same order, and so the file list is derived from the directory
# rather than typed out by hand -- a hand-typed list is how a new migration gets
# left out of the rehearsal and only discovered at the boot.
#
# ONE psql INVOCATION PER MIGRATION, ON PURPOSE
#   Flyway applies each versioned migration in its own transaction, so the
#   rehearsal does too. Passing every file to a single `psql -f a -f b ...`
#   looks tidier and is measurably less useful: on this host a multi-file psql
#   invocation silently stopped after the fourth file and still exited 0, which
#   is the worst possible failure mode for a rehearsal whose entire purpose is to
#   prove the chain applies. One file per invocation means (a) the exit code
#   belongs to exactly one migration, so a failure names its own version, and
#   (b) the post-chain object counts are only ever asserted after the LAST
#   migration has been seen to succeed.
#
#   It also makes every migration correct without any special-casing: a
#   single-file psql session is its own implicit transaction per statement, which
#   is the shape Flyway uses for a script declared executeInTransaction=false.
#   Never add -1 / --single-transaction. (Nothing in the chain needs that
#   declaration any more -- see runbook section 9c for why the CONCURRENTLY
#   migration does not exist -- but the per-file shape is what makes the
#   rehearsal faithful to Flyway regardless.)
#
# NEVER point this at `firstelement`. It refuses to.
#
#   .\ops\flyway\verification\rehearsal-chain.ps1 `
#       -Container firstelement-postgres `
#       -Database firstelement_p710a_check `
#       -ExpectedEnumCount <n> -ExpectedTableCount <n> -ExpectedCheckCount <n> `
#       -EvidenceDir C:\...\evidence\7-10a -ThrowOnViolation
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $Database  = 'firstelement_p710a_check',
    [string] $DbUser    = 'firstelement',
    [string] $EvidenceDir,
    [int]    $ExpectedEnumCount  = 0,
    [int]    $ExpectedTableCount = 0,
    [int]    $ExpectedCheckCount = 0,
    [switch] $Keep,
    [switch] $ThrowOnViolation
)

$ErrorActionPreference = 'Stop'

if ($Database -eq 'firstelement') {
    throw "refusing to rehearse against 'firstelement': the development database is read-only for Agent B. Pick a throwaway name."
}

$repoRoot   = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)))
$sqlDir     = Join-Path $repoRoot 'ops/flyway/sql'
$migrations = Get-ChildItem -Path $sqlDir -Filter 'V*.sql' |
              Where-Object { $_.Name -notlike '*.todo' } |
              Sort-Object { [int64](($_.BaseName -split '__')[0]).Substring(1) }

if ($migrations.Count -eq 0) { throw "no migrations found in $sqlDir" }
Write-Host "chain ($($migrations.Count) migrations, ascending by 14-digit version):"
$migrations | ForEach-Object { Write-Host "  $($_.Name)" }

# Runs docker, returns its combined output as one string. psql writes NOTICE
# lines to stderr, which PowerShell surfaces as NativeCommandError while
# ErrorActionPreference is 'Stop', so stderr is captured and $LASTEXITCODE alone
# decides success -- the arrangement run-verification.ps1 already uses.
function Invoke-Docker {
    param([string[]] $DockerArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& docker @DockerArgs 2>&1) -join "`n"
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0) {
        throw "docker failed (exit ${code}): docker $($DockerArgs -join ' ')`n$out"
    }
    return $out
}

function Invoke-Psql {
    param([string] $Sql, [string] $Db, [string] $Label)
    $out = Invoke-Docker @('exec', $Container, 'psql', '-U', $DbUser, '-d', $Db,
                           '-v', 'ON_ERROR_STOP=1', '-q', '-c', $Sql)
    return $out
}

Write-Host "`n==> recreating throwaway database $Database"
Invoke-Docker @('exec', $Container, 'psql', '-U', $DbUser, '-d', 'postgres', '-q',
                '-v', 'ON_ERROR_STOP=1',
                '-c', "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();",
                '-c', "DROP DATABASE IF EXISTS $Database;",
                '-c', "CREATE DATABASE $Database TEMPLATE template0;") | Out-Null

try {
    foreach ($m in $migrations) {
        Invoke-Docker @('cp', $m.FullName, ("$Container" + ':/tmp/' + $m.Name)) | Out-Null
        Write-Host "==> $($m.Name)"
        Invoke-Docker @('exec', $Container, 'psql', '-U', $DbUser, '-d', $Database,
                        '-v', 'ON_ERROR_STOP=1', '-q', '-f', ('/tmp/' + $m.Name)) | Out-Null
    }

    $counts = Invoke-Psql -Sql @"
select 'tables='   || count(*) from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history';
"@ -Db $Database
    Write-Host "`n$counts"

    if ($EvidenceDir) {
        $bundle = Join-Path $repoRoot 'ops/flyway/verification/run-verification.ps1'
        $bundleArgs = @{
            Container          = $Container
            Database           = $Database
            OutputDir          = $EvidenceDir
            ExpectedEnumCount  = $ExpectedEnumCount
            ExpectedTableCount = $ExpectedTableCount
            ExpectedCheckCount = $ExpectedCheckCount
        }
        if ($ThrowOnViolation) { $bundleArgs.ThrowOnViolation = $true }
        & $bundle @bundleArgs
    }
}
finally {
    if (-not $Keep) {
        Write-Host "`n==> dropping throwaway database $Database"
        Invoke-Docker @('exec', $Container, 'psql', '-U', $DbUser, '-d', 'postgres', '-q',
                        '-v', 'ON_ERROR_STOP=1',
                        '-c', "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Database' AND pid <> pg_backend_pid();",
                        '-c', "DROP DATABASE IF EXISTS $Database;") | Out-Null
    }
}