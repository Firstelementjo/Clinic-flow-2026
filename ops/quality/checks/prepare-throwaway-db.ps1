#!/usr/bin/env pwsh
# =====================================================================
# prepare-throwaway-db.ps1
#
# Creates a DISPOSABLE database inside the PostgreSQL container, optionally
# applies the whole Flyway chain to it, and can dispose of it afterwards.
#
# The one thing this script exists to guarantee is that the database named
# `firstelement` is never the target. Three independent refusals:
#
#   1. the name must differ from -ProtectedDatabase (default `firstelement`);
#   2. the name must LOOK disposable: it has to contain `check`,
#      `rehearsal`, `gate`, `throwaway`, `scratch` or `tmp`. A rehearsal
#      must never be one typo away from development or production;
#   3. the database is created with TEMPLATE template0, so it starts empty
#      no matter what else exists in the cluster.
#
# The script never reads .env and never takes a password: it uses the
# container's local socket through `docker exec`, exactly like
# ops/flyway/verification/run-verification.ps1 does.
#
# Exit codes: 0 ok, 1 refused / failed, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Database,
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $ProtectedDatabase = 'firstelement',

    # Apply every V*.sql in ops/flyway/sql in filename order, concatenated
    # into ONE psql session. One session is deliberate: it is how the
    # Phase-5a rehearsal found that V20260814000100 depended on the
    # baseline's search_path='' rather than being safe under Flyway.
    [switch] $MigrateChain,

    [string] $FlywayDir,

    # Drop the database when finished. Safe to pass even if creation failed.
    [switch] $Dispose
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

$repoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot
if (-not $FlywayDir) { $FlywayDir = Join-Path $repoRoot 'ops\flyway\sql' }

# --- refusals --------------------------------------------------------
if ($Database -eq $ProtectedDatabase) {
    Write-QLog "REFUSING: -Database '$Database' is the protected database. A gate must never create, migrate or drop it." -Level ERROR
    exit 1
}
if ($Database -notmatch '(?i)(check|rehearsal|gate|throwaway|scratch|tmp)') {
    Write-QLog ("REFUSING: '{0}' does not look disposable. A gate database name must contain one of: check, rehearsal, gate, throwaway, scratch, tmp." -f $Database) -Level ERROR
    exit 1
}
if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    Write-QLog "REFUSING: '$Database' is not a plain identifier." -Level ERROR
    exit 1
}

function Invoke-Psql {
    param([string] $Sql, [string] $Db)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db -v ON_ERROR_STOP=1 -t -A -P pager=off -f - 2>&1) |
        ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $previous
    if ($LASTEXITCODE -ne 0) { throw ("psql failed on {0}:`n{1}" -f $Db, ($out -join "`n")) }
    return $out
}

function Remove-Throwaway {
    Write-QLog "disposing $Database"
    $null = Invoke-Psql -Db $ProtectedDatabase -Sql ("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '{0}' AND pid <> pg_backend_pid();" -f $Database)
    $null = Invoke-Psql -Db $ProtectedDatabase -Sql ('DROP DATABASE IF EXISTS "{0}";' -f $Database)
    Write-QLog "dropped $Database"
}

try {
    Write-QLog ("==> [1/2] (re)creating disposable database {0}" -f $Database)
    $null = Invoke-Psql -Db $ProtectedDatabase -Sql ('DROP DATABASE IF EXISTS "{0}";' -f $Database)
    $null = Invoke-Psql -Db $ProtectedDatabase -Sql ('CREATE DATABASE "{0}" TEMPLATE template0;' -f $Database)

    if ($MigrateChain) {
        Write-QLog ("==> [2/2] applying the Flyway chain to {0}" -f $Database)
        $files = @(Get-ChildItem -LiteralPath $FlywayDir -Filter 'V*.sql' -File | Sort-Object Name)
        if ($files.Count -eq 0) { throw ("no V*.sql migration files found in {0}" -f $FlywayDir) }

        $sb = New-Object System.Text.StringBuilder
        foreach ($f in $files) {
            $null = $sb.AppendLine(('-- ' + $f.Name))
            $null = $sb.AppendLine((Get-Content -Raw -LiteralPath $f.FullName))
        }
        $sqlText = $sb.ToString()

        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $out = ($sqlText | docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -q -t -A -P pager=off -f - 2>&1) |
            ForEach-Object { $_.ToString() }
        $code = $LASTEXITCODE
        $ErrorActionPreference = $previous
        foreach ($o in $out) { if ($o) { Write-QLog ('    ' + $o) -Level INFO } }
        if ($code -ne 0) { throw ("applying {0} migration file(s) to {1} failed with exit code {2}" -f $files.Count, $Database, $code) }
        Write-QLog ("    applied {0} migration file(s): {1}" -f $files.Count, (($files | ForEach-Object { $_.Name }) -join ', '))
    }
    else {
        Write-QLog ("==> [2/2] -MigrateChain not requested; {0} is an empty database" -f $Database)
    }

    $probe = Invoke-Psql -Db $Database -Sql 'select current_database();'
    Write-QLog ("    connected to: {0}" -f ($probe | Select-Object -First 1))
}
catch {
    Write-QLog ('FAILED: ' + $_.Exception.Message) -Level ERROR
    if ($Dispose) {
        try { Remove-Throwaway } catch { Write-QLog ('dispose also failed: ' + $_.Exception.Message) -Level ERROR }
    }
    exit 1
}

if ($Dispose) {
    try { Remove-Throwaway } catch { Write-QLog ('dispose failed: ' + $_.Exception.Message) -Level ERROR; exit 1 }
}
exit 0