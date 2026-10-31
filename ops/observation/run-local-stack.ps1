#!/usr/bin/env pwsh
# =====================================================================
# run-local-stack.ps1
# =====================================================================
# Brings up the whole application on one machine, pointed at a database a
# person can actually look at.
#
#   .\ops\observation\run-local-stack.ps1              # start
#   .\ops\observation\run-local-stack.ps1 -Stop       # stop
#   .\ops\observation\run-local-stack.ps1 -Status     # what is running
#
# WHY THIS EXISTS. Everything needed to run this stack existed, in four
# places, and none of them put it together: the performance wrapper knows
# how to boot the API against a disposable database and then destroys it,
# the e2e harness knows how to boot the API and a dev server and then tears
# the tenant down, and the human-test seed knows how to create accounts but
# assumes an empty tenant. The result was that no human had ever been able
# to log in and use the application, and the reason was always three
# correct pieces of automation that had never been run together.
#
# WHAT IT DOES, in order:
#   1. refuses the protected `firstelement` database by name, before
#      anything else -- that database is the frozen legacy reference and
#      the one thing here nobody may mutate;
#   2. creates firstelement_human if it is absent and boots the API once so
#      the Flyway chain applies from empty;
#   3. loads tests/performance/dataset.sql (40,000 customers, 80,000
#      conversations, 400,000 messages) only if the database is empty,
#      because a person signing in to an empty tenant concludes the
#      product is empty;
#   4. creates the three human accounts INSIDE the organization that holds
#      that data, so they see it;
#   5. boots the API on 4000 and the Angular dev server on 4200, which
#      proxies /api to the API, so a browser needs no configuration.
#
# The credential is written to build/observation/human-test-credentials.txt
# and is never printed here.
# =====================================================================

[CmdletBinding()]
param(
    [string] $Database = 'firstelement_human',
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $RepoRoot = $(if ($PSScriptRoot) { Split-Path -Parent (Split-Path -Parent $PSScriptRoot) } else { (Get-Location).Path }),
    [int]    $ApiPort = 4000,
    [int]    $ManagementPort = 4101,
    [int]    $UiPort = 4200,
    [switch] $Stop,
    [switch] $Status
)

$ErrorActionPreference = 'Stop'
$logDir = Join-Path $RepoRoot 'build\observation'

function Write-QLog {
    param([string] $Message, [string] $Level = 'INFO')
    Write-Host ("{0}  {1,-5} {2}" -f (Get-Date).ToString('HH:mm:ss'), $Level, $Message)
}

function Test-Port {
    param([int] $Port)
    return $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Wait-Port {
    param([int] $Port, [int] $Seconds = 120)
    for ($i = 0; $i -lt [int]($Seconds / 5); $i++) {
        if (Test-Port -Port $Port) { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}

function Stop-PortOwner {
    param([int] $Port)
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) {
        $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($p -and $p.ProcessName -in @('java', 'node')) {
            Write-QLog ("stopping the {0} on port {1} (pid {2})" -f $p.ProcessName, $Port, $p.Id) 'WARN'
            try { Stop-Process -Id $p.Id -Force } catch { }
            Start-Sleep -Seconds 3
        }
        else {
            throw ("port {0} is held by {1}, which this script will not touch" -f $Port, $(if ($p) { $p.ProcessName } else { 'a process that has gone' }))
        }
    }
}

if ($Stop) {
    foreach ($port in @($UiPort, $ApiPort, $ManagementPort)) { Stop-PortOwner -Port $port }
    Write-QLog 'local stack stopped' 'STEP'
    exit 0
}

if ($Status) {
    foreach ($pair in @(@{ p = $ApiPort; n = 'API' }, @{ p = $ManagementPort; n = 'management' }, @{ p = $UiPort; n = 'UI' })) {
        Write-QLog ("{0,-11} port {1,-5} {2}" -f $pair.n, $pair.p, $(if (Test-Port -Port $pair.p) { 'LISTENING' } else { 'not running' }))
    }
    $cred = Join-Path $logDir 'human-test-credentials.txt'
    if (Test-Path -LiteralPath $cred) { Write-QLog ("credentials: {0}" -f $cred) }
    exit 0
}

# The refusal comes first, before anything is created or booted.
if ($Database -eq 'firstelement') {
    throw ('refusing to run the local stack against "firstelement". It is the protected legacy ' +
           'reference: 45 tables, deliberately unstamped. This stack runs against ' + $Database + '.')
}

if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }

$jar = Join-Path $RepoRoot 'backend\app-bootstrap\build\libs\firstelement-api-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw "missing $jar. Build it first: gradlew :backend:app-bootstrap:bootJar"
}

Write-QLog ("local stack -> database {0}" -f $Database) 'STEP'

foreach ($port in @($UiPort, $ApiPort, $ManagementPort)) { Stop-PortOwner -Port $port }

# --- 1. does the database exist? --------------------------------------
$exists = (docker exec $Container psql -U $DbUser -d postgres -t -A -c "SELECT count(*) FROM pg_database WHERE datname = '$Database';") -eq '1'
if (-not $exists) {
    Write-QLog "creating $Database (empty; the API applies the Flyway chain on boot)" 'STEP'
    $null = docker exec $Container psql -U $DbUser -d postgres -q -c "CREATE DATABASE $Database;" 2>&1
}

function Start-Api {
    param([switch] $FirstBoot)
    $env:JAVA_TOOL_OPTIONS = '-Djava.net.preferIPv4Stack=true'
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/$Database"
    $args = @('-Djava.net.preferIPv4Stack=true', '-jar', $jar, "--server.port=$ApiPort", "--management.server.port=$ManagementPort")
    $p = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput (Join-Path $logDir 'api.out') -RedirectStandardError (Join-Path $logDir 'api.err') `
        -WindowStyle Hidden -PassThru
    if (-not (Wait-Port -Port $ApiPort -Seconds 180)) {
        throw "the API never listened on $ApiPort. See build/observation/api.out"
    }
    Write-QLog ("API listening on {0} (management {1}, pid {2})" -f $ApiPort, $ManagementPort, $p.Id) 'STEP'
    return $p
}

# --- 2. migrate from empty if needed -----------------------------------
if (-not $exists) {
    $api = Start-Api -FirstBoot
    Start-Sleep -Seconds 5
    Stop-PortOwner -Port $ApiPort
    Start-Sleep -Seconds 3
}

# --- 3. data, only if the tenant is empty ------------------------------
$customers = [int64]((docker exec $Container psql -U $DbUser -d $Database -t -A -c "select count(*) from customers;") | Select-Object -First 1)
if ($customers -eq 0) {
    Write-QLog 'loading tests/performance/dataset.sql (40,000 customers / 400,000 messages)' 'STEP'
    $hash = '$2a$12$LQv3c6GHIx0zJhhkZ5mB9eVwRt2Yu7h1sA8dF4gP0oK6nXcJ3WbNq'
    $seed = [System.IO.File]::ReadAllText((Join-Path $RepoRoot 'tests\performance\dataset.sql')).Replace('$fe_perf_bcrypt$', $hash)
    $null = ($seed | docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -q -t -A -f - 2>&1)
    Write-QLog ("data loaded: {0} customers" -f [int64]((docker exec $Container psql -U $DbUser -d $Database -t -A -c "select count(*) from customers;") | Select-Object -First 1)) 'STEP'
}
else {
    Write-QLog ("tenant already holds {0} customers; leaving the data alone" -f $customers) 'INFO'
}

# --- 4. the three accounts, INSIDE the organization that holds it ------
# The query goes through STDIN, not `docker exec -c`. Windows re-parses the
# docker.exe command line and strips the embedded double quotes around
# "organizationId", so PostgreSQL folds it to `p.organizationid` and answers
# `column p.organizationid does not exist`. That aborted this script before it
# started the API or the UI, which is why a "one command" deploy could not
# restore a stack on Windows. `seed-human-test.ps1` is immune because its
# Invoke-Psql already pipes to `-f -`.
$orgSql = 'select o.id, p.id from organizations o join projects p on p."organizationId" = o.id order by o.slug, p.slug limit 1;'
$orgRow = ($orgSql | docker exec -i $Container psql -U $DbUser -d $Database -t -A -F '|' -f - 2>&1) | Select-Object -First 1
$parts = ([string]$orgRow) -split '\|'
if ($parts.Count -lt 2) { throw 'could not resolve an organization and project to place the human accounts in' }
$null = & (Join-Path $PSScriptRoot 'seed-human-test.ps1') -RepoRoot $RepoRoot -Database $Database `
    -ExistingOrganizationId $parts[0] -ExistingProjectId $parts[1]

# --- 5. the two long-running processes ---------------------------------
$null = Start-Api
$ng = Join-Path $RepoRoot 'frontend\node_modules\.bin\ng.cmd'
if (-not (Test-Path -LiteralPath $ng)) { throw "missing $ng. Install the frontend first: npm --prefix frontend install" }
Start-Process -FilePath 'cmd.exe' `
    -ArgumentList @('/c', "cd /d `"$(Join-Path $RepoRoot 'frontend')`" && `"$ng`" serve --port $UiPort --host 127.0.0.1 > `"$(Join-Path $logDir 'ui.log')`" 2>&1") `
    -WorkingDirectory (Join-Path $RepoRoot 'frontend') -WindowStyle Hidden
if (-not (Wait-Port -Port $UiPort -Seconds 180)) { throw "the UI never listened on $UiPort. See build/observation/ui.log" }

Write-QLog ("local stack is UP") 'STEP'
Write-Host ''
Write-Host ("  application : http://localhost:{0}" -f $UiPort)
Write-Host ("  api         : http://localhost:{0}" -f $ApiPort)
Write-Host ("  credentials : {0}" -f (Join-Path $logDir 'human-test-credentials.txt'))
Write-Host ''
Write-Host '  Sign in as admin@human.invalid, employee@human.invalid or clinic@human.invalid.'
Write-Host '  They hold different permissions; a single account would not show you the system.'
Write-Host ''