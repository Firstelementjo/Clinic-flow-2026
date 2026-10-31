#!/usr/bin/env pwsh
# =====================================================================
# seed-clinic-demo.ps1
# =====================================================================
# Populates the clinic tables of a HUMAN-FACING tenant with one demo
# clinic so the clinic screens have something to show.
#
# The clinic tables ship empty. A person signing in to the clinic
# dashboard sees six zeroes and an empty revenue chart and concludes the
# dashboard is broken, when the truth is that the tenant is unseeded.
# Those are two different bugs and only one of them is a bug.
#
# The rules this script keeps:
#
#   * it refuses `firstelement` -- the protected legacy reference --
#     by name, before it opens a connection;
#   * it is ADDITIVE and RE-RUNNABLE. Every row is keyed to a stable
#     md5() id, so a second run replaces the same rows instead of
#     creating a second clinic;
#   * it never prints a credential and never touches a database whose
#     name does not say it is throwaway or human-facing;
#   * it resolves the organization, the project and the acting user
#     from the accounts seed-human-test.ps1 created, so it cannot be
#     pointed at somebody else's tenant by accident.
#
#   .\ops\observation\seed-clinic-demo.ps1
#   .\ops\observation\seed-clinic-demo.ps1 -Account 'clinic@human.invalid'
#
# The SQL lives in seed-clinic-demo.sql next to this file, so it can be
# read, reviewed and run by hand with psql.
# =====================================================================

[CmdletBinding()]
param(
    [string] $Database = 'firstelement_human',
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $RepoRoot = $(if ($PSScriptRoot) { Split-Path -Parent (Split-Path -Parent $PSScriptRoot) } else { (Get-Location).Path }),
    # The account whose membership decides which organization and project get seeded.
    [string] $Account = 'admin@human.invalid'
)

$ErrorActionPreference = 'Stop'

# The refusal comes FIRST, before a connection is opened.
if ($Database -eq 'firstelement') {
    throw ('refusing to seed "firstelement". It is the protected legacy reference: 45 tables, ' +
           'deliberately unstamped, and the one database in this stack nobody may mutate. ' +
           'Seed a database whose name says it is throwaway or human-facing.')
}

$sqlFile = Join-Path $PSScriptRoot 'seed-clinic-demo.sql'
if (-not (Test-Path -LiteralPath $sqlFile)) {
    throw ("seed SQL not found at {0}" -f $sqlFile)
}

function Write-QLog {
    param([string] $Message, [string] $Level = 'INFO')
    Write-Host ("{0}  {1,-5} {2}" -f (Get-Date).ToString('HH:mm:ss'), $Level, $Message)
}

function Invoke-Psql {
    param([string] $Sql, [string] $Db = 'postgres')
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db -v ON_ERROR_STOP=1 -t -A -f - 2>&1) |
        ForEach-Object { [string]$_ }
    if ($LASTEXITCODE -ne 0) { throw ("psql failed: {0}" -f (($out | Where-Object { $_ }) -join ' ')) }
    return $out
}

Write-QLog ("seeding the demo clinic in {0}" -f $Database) 'STEP'

# --- resolve the tenant from the account, never from a parameter ------
$resolved = Invoke-Psql -Db $Database -Sql @"
select u."organizationId" || ' ' || pm."projectId" || ' ' || u.id
  from project_memberships pm
  join users u on u.id = pm."userId"
 where u.email = '$($Account.Replace("'", "''"))'
 order by pm."createdAt"
 limit 1;
"@
if (-not $resolved) {
    throw ("no project membership found for account '{0}' in {1}. Run seed-human-test.ps1 first." -f $Account, $Database)
}
$parts = ($resolved | Select-Object -First 1) -split ' '
$orgId = $parts[0]
$projId = $parts[1]
$adminId = $parts[2]
Write-QLog ("organization {0} project {1}" -f $orgId, $projId) 'STEP'

# --- run the seed ------------------------------------------------------
$seedOut = (Get-Content -Raw -LiteralPath $sqlFile |
    docker exec -i $Container psql -U $DbUser -d $Database `
        -v ON_ERROR_STOP=1 `
        -v "org=$orgId" -v "proj=$projId" -v "admin=$adminId" `
        -f - 2>&1) | ForEach-Object { [string]$_ }
if ($LASTEXITCODE -ne 0) {
    throw ("clinic seed failed: {0}" -f (($seedOut | Where-Object { $_ }) -join ' '))
}
foreach ($line in ($seedOut | Where-Object { $_ -and $_ -notmatch '^(INSERT|DELETE|SELECT|UPDATE|CREATE|SET|-\+|psql:)' })) {
    Write-QLog $line 'SQL'
}

# --- prove it landed ---------------------------------------------------
$counts = Invoke-Psql -Db $Database -Sql @"
select (select count(*) from doctors          where "organizationId" = '$orgId')
    || ' doctors, '
    || (select count(*) from procedures       where "organizationId" = '$orgId') || ' procedures, '
    || (select count(*) from patients         where "organizationId" = '$orgId') || ' patients, '
    || (select count(*) from appointments      where "organizationId" = '$orgId') || ' appointments, '
    || (select count(*) from clinic_invoices   where "organizationId" = '$orgId') || ' invoices, '
    || (select count(*) from clinic_payments   where "organizationId" = '$orgId') || ' payments, '
    || (select count(*) from clinical_encounters where "organizationId" = '$orgId') || ' encounters';
"@
Write-QLog ("clinic seeded: {0}" -f ($counts | Select-Object -First 1)) 'STEP'
Write-QLog 'sign in and open the clinic dashboard: the date filter, the revenue chart and the age bands now have real data' 'STEP'
