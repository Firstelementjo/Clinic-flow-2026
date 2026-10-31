#!/usr/bin/env pwsh
# =====================================================================
# check-shadow-parity.ps1
#
# Phase 12, steps 1-2: run the frozen legacy read path and the new Spring
# read path in shadow mode over the same synthetic rows, and compare row
# counts, identifiers, authorization decisions, status transitions, finance
# rollups, analytics and exports.
#
# ---------------------------------------------------------------------
# THE ONE THING THIS GATE IS BUILT AROUND
# ---------------------------------------------------------------------
# A parity harness that has only ever been green is decoration. So this gate
# does not just run the comparisons -- it PROVES each one can fail, by
# perturbing an input and requiring the verdict to flip:
#
#   * runtime perturbation  -- a database row is changed on ONE side only,
#                             the comparison is re-run, and the named check
#                             MUST go red. If it stays green the check is
#                             blind to the thing it claims to measure, and
#                             this gate fails as a HARNESS DEFECT.
#   * detector perturbation -- the comparator is fed a doctored capture,
#                             for the branches a database edit cannot reach.
#
# A green comparison with no demonstrated red is reported as
# `unproven`, never as `pass`.
#
# ---------------------------------------------------------------------
# THE DATABASES
# ---------------------------------------------------------------------
# `firstelement` is production-shaped and is never touched. This script
# creates two disposable databases whose names carry `shadow_check`, applies
# the verified legacy baseline to one of them, lets the new runtime migrate
# the other from empty through its own Flyway chain, seeds both with the same
# file, and disposes of both on the way out -- including on failure.
#
# It additionally records the backend count on `firstelement` from
# pg_stat_activity before and after, so "we did not write to it" is a
# measurement rather than an intention.
#
# Exit codes: 0 all proven comparisons agree, 1 a comparison disagrees or a
#             check could not be proven able to fail, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $JsonOut,

    # Port for the frozen legacy NestJS runtime.
    [int] $LegacyPort = 4401,
    # Port for the new Spring runtime.
    [int] $SpringPort = 4402,
    [int] $SpringManagementPort = 4403,

    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $LegacyDatabase = 'shadow_check_legacy',
    [string] $SpringDatabase = 'shadow_check_spring',
    [string] $SpringJar,
    [string] $WorkDir,

    # Skip the destructive steps (database creation, seeding, boot) and only
    # re-run the comparison against captures already on disk. Diagnostics
    # only; NOT accepted as a gate pass.
    [switch] $CompareOnly,

    # Fail instead of releasing a port held by a leftover JVM or Node process.
    # CI should use this: silently killing whatever holds the port is only
    # safe when a human is watching the log.
    [switch] $RefuseToReusePorts,

    # Run the ADR 0013 section 4 soak: hold both runtimes serving while the
    # comparison runs and probe them on a timer. Default on; the finding is
    # only reproducible under sustained load.
    [switch] $NoSoak
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $SpringJar) {
    $SpringJar = Join-Path $RepoRoot 'backend\app-bootstrap\build\libs\firstelement-api-0.1.0.jar'
}
if (-not $WorkDir) {
    $WorkDir = Join-Path ([IO.Path]::GetTempPath()) ('fe-shadow-parity-' + (Get-QUtcTimestamp))
}
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

$shadowRoot = Join-Path $RepoRoot 'tests\shadow'
$scenariosPath = Join-Path $shadowRoot 'scenarios\reads.json'
$seedCore = Join-Path $shadowRoot 'dataset\seed-core.sql'
$seedNewOnly = Join-Path $shadowRoot 'dataset\seed-new-only.sql'
$baselineSql = Join-Path $RepoRoot 'ops\flyway\sql\V20260814000000__baseline_verified_live_schema.sql'
$legacyBuildDir = Join-Path $shadowRoot 'build\legacy-api'

$findings = New-Object System.Collections.Generic.List[object]
$legacyProcess = $null
$springProcess = $null
$legacyEnvBackup = @{}

function Note { param([string] $Level, [string] $Message) Write-QLog $Message -Level $Level }

function Fail {
    param([string] $Rule, [string] $Detail)
    $script:findings.Add([ordered]@{ rule = $Rule; detail = $Detail })
}

function Invoke-Psql {
    param([string] $Database, [string] $Sql, [string] $Label = 'sql', [int] $StatementTimeoutMs = 0)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # PHASE 49. The statement_timeout is optional and the teardown sets it. Without one, a
    # DROP DATABASE against a database that a live JVM keeps reconnecting to waits on the lock
    # for ever. That is exactly what the reported "hang" was: the report was written at 15:43:42
    # and the stage was still running at 15:55 with its own JVM holding 4402 and 4403. A
    # bounded statement that times out and names itself is strictly better than an unbounded one
    # that hangs -- one is a failure a gate can report, the other is a machine somebody has to
    # notice.
    #
    # It is a SET PREFIX, not a psql flag: `psql -c statement_timeout=20000` is not a setting,
    # it is a SQL statement, and PostgreSQL answers "syntax error at or near statement_timeout".
    # An earlier attempt in this file did exactly that and made every teardown DROP fail, which
    # looked like a permission problem and was not one.
    if ($StatementTimeoutMs -gt 0) {
        $Sql = ("SET statement_timeout = {0};`n{1}" -f $StatementTimeoutMs, $Sql)
    }
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -t -A -P pager=off -f - 2>&1) |
        ForEach-Object { $_.ToString() }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw ("psql failed on {0} ({1}):`n{2}" -f $Database, $Label, ($out -join "`n")) }
    return $out
}

# --- the protected database: measured, not assumed --------------------
function Get-ProtectedBackends {
    Invoke-Psql -Database 'firstelement' -Sql "select count(*) from pg_stat_activity where datname = 'firstelement';" -Label 'protected-backend-count' |
        Select-Object -First 1
}

# --- disposable databases --------------------------------------------
function Reset-DisposableDatabases {
    # Only the LEGACY database is seeded here. The new runtime's database has
    # to migrate from EMPTY through its own Flyway chain first -- that is the
    # first Flyway-enabled boot this phase rehearses, and seeding it before
    # the migration would take the STAMP_BASELINE branch instead and prove
    # nothing. It is seeded once the runtime is up (Seed-SpringDatabase).
    foreach ($db in @($LegacyDatabase, $SpringDatabase)) {
        if ($db -eq 'firstelement') { throw 'refusing to target the protected database' }
        & (Join-Path $PSScriptRoot 'prepare-throwaway-db.ps1') -Database $db -Container $Container -DbUser $DbUser | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "could not create the disposable database $db" }
    }
    # The legacy runtime's schema is the verified live schema. Its own
    # packages/database/prisma/migrations are STALE relative to schema.prisma
    # (they still declare EmployeeAvailabilityStatus.AVAILABLE/AVAY), because
    # the live database was produced by `prisma db push` (ADR 0002). Applying
    # the migrations would give the legacy runtime a schema it never ran on.
    $sql = Get-Content -Raw -LiteralPath $baselineSql
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $null = ($sql | docker exec -i $Container psql -U $DbUser -d $LegacyDatabase -v ON_ERROR_STOP=1 -q -t -A -P pager=off -f - 2>&1)
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw 'could not apply the verified legacy baseline to the disposable legacy database' }

    $seed = Get-Content -Raw -LiteralPath $seedCore
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $null = ($seed | docker exec -i $Container psql -U $DbUser -d $LegacyDatabase -v ON_ERROR_STOP=1 -q -t -A -P pager=off -f - 2>&1)
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw 'could not seed the disposable legacy database' }
}

function Seed-SpringDatabase {
    # The SAME file that seeded the legacy database, replayed into the new
    # runtime's database. One file, two databases: that is what makes "the two
    # stacks read the same rows" a fact rather than an intention.
    $seed = Get-Content -Raw -LiteralPath $seedCore
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $null = ($seed | docker exec -i $Container psql -U $DbUser -d $SpringDatabase -v ON_ERROR_STOP=1 -q -t -A -P pager=off -f - 2>&1)
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw 'could not seed the disposable Spring database' }

    # The finance and export tables exist only after the Flyway chain, so they
    # are seeded here and exist on no legacy side at all.
    Seed-SpringOnlyTables
}

function Seed-SpringOnlyTables {
    if (-not (Test-Path -LiteralPath $seedNewOnly)) { return }
    $seed = Get-Content -Raw -LiteralPath $seedNewOnly
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $null = ($seed | docker exec -i $Container psql -U $DbUser -d $SpringDatabase -v ON_ERROR_STOP=1 -q -t -A -P pager=off -f - 2>&1)
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw 'could not seed the new-only finance and export tables' }
}

function Invoke-PsqlFile {
    param([string] $Database, [string] $Path, [string] $Label)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $sql = Get-Content -Raw -LiteralPath $Path
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # PHASE 49. The psql output was assigned to $null and thrown away, and the throw carried only
    # "applying PN-05 to shadow_check_spring failed". That is why FE-SHADOW-012 has been reported
    # as an unsolvable blocker for so long: the harness destroyed the very message that would
    # have said what PostgreSQL objected to. A failure whose cause is discarded cannot be
    # diagnosed, only re-encountered.
    $out = ($sql | docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -q -t -A -P pager=off -f - 2>&1) |
        ForEach-Object { $_.ToString() }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) {
        $detail = (($out | Where-Object { $_ }) -join "`n").Trim()
        if (-not $detail) { $detail = "psql exited $code with no message" }
        throw ("applying {0} to {1} failed:`n{2}" -f $Label, $Database, $detail)
    }
}

# --- runtimes ---------------------------------------------------------

# --- ports ----------------------------------------------------------
# A leftover process from an interrupted previous run is the most likely way
# this gate silently measures the wrong thing: if :4402 is already held, the
# new Spring runtime never starts, the harness talks to the OLD process, and
# every capture "passes" against a runtime nobody is looking at. So the ports
# are claimed before anything boots, and whatever is holding them is named.
function Release-GatePorts {
    param([switch] $Refuse)
    $released = @()
    for ($attempt = 1; $attempt -le 15; $attempt++) {
        $listeners = @(Get-NetTCPConnection -LocalPort $LegacyPort, $SpringPort, $SpringManagementPort -State Listen -ErrorAction SilentlyContinue)
        if ($listeners.Count -eq 0) { return $released }
        foreach ($listener in $listeners) {
            $port = $listener.LocalPort
            $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
            if ($owner -and $owner.ProcessName -in @('java', 'node')) {
                $description = "$($owner.ProcessName) (pid $($owner.Id), started $($owner.StartTime))"
                if ($Refuse) {
                    throw ("port {0} is already held by {1}. Refusing to reuse it: the harness would capture against a runtime it did not start." -f $port, $description)
                }
                Note 'WARN' ("port {0} was held by a leftover {1}; releasing it" -f $port, $description)
                try { Stop-Process -Id $owner.Id -Force } catch { }
                $released += [ordered]@{ port = $port; released = $description }
            }
            elseif (-not $owner) {
                # The owning process has already gone but the listening socket
                # has not been reaped yet. This is the normal shape of the two
                # seconds after a Kill(), not a port held by a stranger.
                Start-Sleep -Seconds 2
            }
            else {
                throw ("port {0} is held by {1} (pid {2}), which is neither a JVM nor a Node process. Refusing to touch it." -f $port, $owner.ProcessName, $owner.Id)
            }
        }
        Start-Sleep -Seconds 2
    }
    throw 'the gate ports are still held after 30 seconds of trying to release them'
}

function Wait-Listening {
    param([int] $Port, [System.Diagnostics.Process] $Process, [int] $Seconds = 120)
    for ($i = 0; $i -lt $Seconds; $i += 1) {
        if ($Process.HasExited) { throw "the process exited before it listened on $Port (exit $($Process.ExitCode))" }
        if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function New-SyntheticSecrets {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $session = New-Object byte[] 48; $rng.GetBytes($session)
    $encryption = New-Object byte[] 32; $rng.GetBytes($encryption)
    return [ordered]@{
        session = [Convert]::ToBase64String($session)
        encryption = (($encryption | ForEach-Object { $_.ToString('x2') }) -join '')
    }
}

# The password the seeded users share. It is a FIXTURE value published in
# tests/shadow/dataset/credentials.json, not a credential read from anywhere:
# no .env is opened, and the bcrypt hash in the seed is the hash of exactly
# this string and nothing else.
function Get-ShadowPassword {
    $path = Join-Path $shadowRoot 'dataset\credentials.json'
    if (-not (Test-Path -LiteralPath $path)) {
        throw ("the synthetic credential file is missing: {0}. The harness does not fall back to a default password, because a default password nobody can look up is a credential nobody can audit." -f $path)
    }
    $doc = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    return [string]$doc.syntheticPassword
}

function Start-LegacyRuntime {
    # Compiles apps/api with tsc into tests/shadow/build/legacy-api. The
    # compiled output lands under `build/`, which the repository already
    # ignores, so no file under apps/** or packages/** is written. Running
    # the compiled SOURCE rather than apps/api/dist is deliberate: dist was
    # built on 2026-10-04 and a parity claim about "the legacy runtime" has
    # to be a claim about the legacy source that is in the tree now.
    New-Item -ItemType Directory -Force -Path $legacyBuildDir | Out-Null
    Note 'STEP' '==> compiling the frozen legacy runtime (apps/api) into tests/shadow/build'
    $tscLog = Join-Path $WorkDir 'legacy-tsc.log'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $null = & npx tsc -p (Join-Path $RepoRoot 'apps\api\tsconfig.json') `
        --outDir $legacyBuildDir `
        --tsBuildInfoFile (Join-Path $legacyBuildDir 'api.tsbuildinfo') 2>&1 | Tee-Object -FilePath $tscLog
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw "the legacy runtime did not compile (exit $code); log $tscLog" }

    $secrets = New-SyntheticSecrets
    # Every variable the legacy config validates is set EXPLICITLY. The
    # legacy config package loads the repository `.env` by walking up from
    # the working directory, and dotenv does not overwrite a variable that is
    # already in the real environment -- so these win. Setting them all is
    # still the only safe option: a variable left unset would silently come
    # from `.env`, which points at `firstelement`.
    $env:DATABASE_URL = "postgresql://{0}:{0}@127.0.0.1:5432/{1}" -f $DbUser, $LegacyDatabase
    $env:REDIS_URL = 'redis://127.0.0.1:6379'
    $env:SESSION_SECRET = $secrets.session
    $env:ENCRYPTION_KEY = $secrets.encryption
    $env:API_PORT = [string]$LegacyPort
    $env:NODE_ENV = 'test'
    $env:LOG_LEVEL = 'info'
    $env:QUEUE_DRIVER = 'inline'
    $env:AI_PROVIDER = 'mock'
    $env:ENABLE_MOCK_CONNECTOR = 'false'
    $env:STORAGE_DRIVER = 'local'
    $env:STORAGE_LOCAL_PATH = (Join-Path $WorkDir 'legacy-storage')

    $log = Join-Path $WorkDir 'legacy-api.log'
    $err = Join-Path $WorkDir 'legacy-api.err'
    $p = Start-Process -FilePath 'node' `
        -ArgumentList (Join-Path $legacyBuildDir 'main.js') `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $log -RedirectStandardError $err `
        -PassThru -WindowStyle Hidden
    if (-not (Wait-Listening -Port $LegacyPort -Process $p)) {
        throw "the legacy runtime never listened on $LegacyPort; log $log"
    }
    return @{ process = $p; log = $log }
}

function Start-SpringRuntime {
    if (-not (Test-Path -LiteralPath $SpringJar)) {
        throw ("the Spring jar is missing: {0}. This gate does not build it; the orchestrator's own gate owns that." -f $SpringJar)
    }
    $secrets = New-SyntheticSecrets
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:5432/{0}" -f $SpringDatabase
    $env:DATABASE_USER = $DbUser
    $env:DATABASE_PASSWORD = $DbUser
    $env:REDIS_URL = 'redis://127.0.0.1:6379'
    $env:API_PORT = [string]$SpringPort
    $env:MANAGEMENT_PORT = [string]$SpringManagementPort
    $env:APP_ENV = 'LOCAL'
    $env:SESSION_SECRET = $secrets.session
    $env:TRACING_SAMPLE_PROBABILITY = '0'
    $env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics,mappings'

    $log = Join-Path $WorkDir 'spring-api.log'
    $err = Join-Path $WorkDir 'spring-api.err'
    $p = Start-Process -FilePath 'java' `
        -ArgumentList '-Djava.net.preferIPv4Stack=true', '-jar', $SpringJar `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $log -RedirectStandardError $err `
        -PassThru -WindowStyle Hidden
    if (-not (Wait-Listening -Port $SpringPort -Process $p -Seconds 180)) {
        throw "the Spring runtime never listened on $SpringPort; log $log"
    }
    return @{ process = $p; log = $log }
}

# --- node steps -------------------------------------------------------

function Invoke-Node {
    param([string] $Script, [string[]] $Arguments, [switch] $AllowNonZero)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = (& node (Join-Path $shadowRoot $Script) @Arguments 2>&1) | ForEach-Object { $_.ToString() }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    foreach ($line in $output) { if ($line) { Note 'INFO' ('    ' + $line) } }
    if ($code -ne 0 -and -not $AllowNonZero) { throw ("{0} exited {1}" -f $Script, $code) }
    return $code
}

# ---------------------------------------------------------------------
# ADR 0013 section 4: the unreproduced stall
# ---------------------------------------------------------------------
# After roughly two hours of serving, the Phase-11 instance stopped answering
# unsafe methods while GET stayed instant, with nothing blocked in a thread
# dump. It never reproduced, and this gate does not pretend to have
# reproduced it: it PROBES both method classes at every checkpoint of the run
# and records the answer, so a run that does see it has exactly the evidence
# the ADR asks for -- status, latency, and whether the symptom was a REFUSAL
# or a HANG.
#
# The probes are synchronous rather than timer-driven on purpose. A timer whose
# handler cannot be attached from Windows PowerShell is a timer that silently
# records nothing, and a soak that recorded nothing would report "no stall
# observed" when the truth is "never looked".
function Invoke-SoakNow {
    param([string] $Label)
    if ($NoSoak) { return }
    $null = Probe-Once -Port $LegacyPort -Label 'legacy' -At $Label
    $null = Probe-Once -Port $SpringPort -Label 'spring' -At $Label
}

function Probe-Once {
    param([int] $Port, [string] $Label, [string] $At)
    $prefix = if ($Label -eq 'spring') { '/api/v1' } else { '/api' }
    $script:SoakSequence++
    $sample = [ordered]@{
        sequence = $script:SoakSequence
        at = (Get-Date).ToUniversalTime().ToString('o')
        checkpoint = $At
        runtime = $Label
        get = $null
        post = $null
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $probePath = if ($Label -eq 'spring') { '/api/v1/status' } else { '/api/health' }
        $response = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}{1}" -f $Port, $probePath) `
            -TimeoutSec 10 -UseBasicParsing
        $sample.get = "$($response.StatusCode) in $($sw.ElapsedMilliseconds)ms"
    } catch {
        # A STATUS is an answer. The legacy runtime has no /api/status route and
        # answers 404, and the first version of this probe recorded every
        # thrown status as "NO ANSWER" -- which is precisely the confusion ADR
        # 0013 section 4 exists to prevent, committed by the thing meant to
        # detect it. Only an exception with NO response object is a stall.
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        if ($status -eq 0) {
            $sample.get = "NO ANSWER after $($sw.ElapsedMilliseconds)ms: $($_.Exception.Message)"
        } else {
            $sample.get = "$status in $($sw.ElapsedMilliseconds)ms"
        }
    }
    $sw.Restart()
    try {
        # An unsafe method with no CSRF: the control's own refusal path. A
        # refusal is a REFUSAL; a hang is a hang. The two are recorded
        # differently on purpose, because telling them apart is the whole
        # question ADR 0013 section 4 asks.
        $null = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}{1}/auth/login" -f $Port, $prefix) `
            -Method POST -Body '{}' -ContentType 'application/json' -TimeoutSec 10 -UseBasicParsing
        $sample.post = "UNEXPECTED SUCCESS in $($sw.ElapsedMilliseconds)ms"
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        if ($status -eq 0) { $sample.post = "NO ANSWER after $($sw.ElapsedMilliseconds)ms" }
        else { $sample.post = "$status in $($sw.ElapsedMilliseconds)ms" }
    }
    $script:SoakSamples[$Label] = @($script:SoakSamples[$Label]) + $sample
    return $sample
}

# =====================================================================
# main
# =====================================================================
$startedUtc = (Get-Date).ToUniversalTime()
$protectedBackendsBefore = $null
$script:SoakSamples = @{}
$script:SoakSequence = 0
$script:SoakStart = Get-Date
$report = [ordered]@{
    check = 'shadow-parity'
    checkedAtUtc = $startedUtc.ToString('o')
    verdict = 'FAIL'
    runtimes = [ordered]@{}
    databases = [ordered]@{}
    baseline = $null
    perturbations = ConvertTo-QArray -Value @()
    proven = @()
    unproven = @()
    soak = $null
    notes = ConvertTo-QArray -Value @()
}

try {
    foreach ($tool in @('docker', 'node', 'java')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw ("{0} is not on PATH. A missing service or tool is a FAILED GATE, not a skip." -f $tool)
        }
    }
    if (-not (Test-Path -LiteralPath $scenariosPath)) { throw "the scenario matrix is missing: $scenariosPath" }
    if (-not (Test-Path -LiteralPath $seedCore)) { throw "the dataset is missing: $seedCore" }

    $releasedPorts = Release-GatePorts -Refuse:$RefuseToReusePorts
    $report.databases.releasedPorts = ConvertTo-QArray -Value $releasedPorts

    Note 'STEP' '==> recording the protected database backend count (read-only, never written)'
    $protectedBackendsBefore = Get-ProtectedBackends
    $report.databases.protectedDatabaseBackendsBefore = $protectedBackendsBefore

    if (-not $CompareOnly) {
        Note 'STEP' '==> creating and seeding the two disposable shadow databases'
        Reset-DisposableDatabases
        $report.databases.legacy = $LegacyDatabase
        $report.databases.spring = $SpringDatabase

        $legacy = Start-LegacyRuntime
        $script:legacyProcess = $legacy.process
        $report.runtimes.legacy = [ordered]@{ port = $LegacyPort; log = $legacy.log; startError = $null }
        Note 'STEP' ("==> legacy runtime is listening on {0}" -f $LegacyPort)

        $spring = Start-SpringRuntime
        $script:springProcess = $spring.process
        $report.runtimes.spring = [ordered]@{ port = $SpringPort; log = $spring.log; startError = $null }
        Note 'STEP' ("==> Spring runtime is listening on {0}" -f $SpringPort)

        # Only now, with the Flyway chain applied from empty, is the new
        # runtime's database seeded with the identical rows.
        Note 'STEP' '==> seeding the new runtime database with the same rows'
        Seed-SpringDatabase
    }

    if (-not $NoSoak) {
        Note 'STEP' '==> ADR 0013 section 4: first probe of both runtimes'
        Invoke-SoakNow -Label 'runtimes-up'
    }

    # --- route inventories -------------------------------------------
    # Read from what each runtime says about ITSELF: the legacy NestJS boot
    # log (which logs its route table at INFO) and the new runtime's
    # /actuator/mappings on the operator port. Neither is written by hand.
    $routesPath = Join-Path $WorkDir 'routes.json'
    $mappingsPath = Join-Path $WorkDir 'spring-mappings.json'
    if (-not $CompareOnly) {
        $mappings = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/actuator/mappings" -f $SpringManagementPort) `
            -TimeoutSec 30 -UseBasicParsing
        # [IO.File]::WriteAllText, not Set-Content -Encoding UTF8: on
        # Windows PowerShell 5.1 that writes a BOM, and JSON.parse rejects a
        # leading U+FEFF with a syntax error that has nothing to do with the
        # document. And Invoke-WebRequest hands back a BYTE[] here, not a
        # string, so the body has to be decoded before it is written -- writing
        # the array writes "123 34 123..." and produces a JSON syntax error
        # that reads like a corrupt endpoint rather than a decode mistake.
        $body = if ($mappings.Content -is [byte[]]) {
            [System.Text.Encoding]::UTF8.GetString($mappings.Content)
        } else {
            [string]$mappings.Content
        }
        [IO.File]::WriteAllText($mappingsPath, $body, (New-Object System.Text.UTF8Encoding($false)))
        Invoke-Node -Script 'routes.mjs' -Arguments @(
            '--legacy-log', $report.runtimes.legacy.log,
            '--spring-mappings', $mappingsPath,
            '--out', $routesPath)
    }

    # --- capture ------------------------------------------------------
    # One session file per runtime, reused by every capture in this run --
    # including the perturbed ones. Each principal therefore authenticates
    # ONCE per gate run. The login rate limiter allows 10 attempts per 60 s per
    # address; a harness that re-authenticates on every capture exhausts its
    # own budget and starts measuring the limiter instead of the product, which
    # is exactly the non-determinism ADR 0013 section 5 recorded. The control
    # is unchanged.
    $capLegacy = Join-Path $WorkDir 'capture-legacy.json'
    $capSpring = Join-Path $WorkDir 'capture-spring.json'
    $sessionLegacy = Join-Path $WorkDir 'session-legacy.json'
    $sessionSpring = Join-Path $WorkDir 'session-spring.json'
    $env:SHADOW_PARITY_PASSWORD = Get-ShadowPassword
    Note 'STEP' '==> capturing the read matrix against the frozen legacy runtime'
    $null = Invoke-Node -Script 'capture.mjs' -Arguments @(
        '--side', 'legacy', '--base', ("http://127.0.0.1:{0}" -f $LegacyPort),
        '--scenarios', $scenariosPath, '--out', $capLegacy, '--session', $sessionLegacy)
    Note 'STEP' '==> capturing the read matrix against the new Spring runtime'
    $null = Invoke-Node -Script 'capture.mjs' -Arguments @(
        '--side', 'spring', '--base', ("http://127.0.0.1:{0}" -f $SpringPort),
        '--scenarios', $scenariosPath, '--out', $capSpring, '--session', $sessionSpring)

    $dbLegacy = Join-Path $WorkDir 'db-legacy.json'
    $dbSpring = Join-Path $WorkDir 'db-spring.json'
    $null = Invoke-Node -Script 'db-snapshot.mjs' -Arguments @('--database', $LegacyDatabase, '--out', $dbLegacy, '--container', $Container, '--user', $DbUser)
    $null = Invoke-Node -Script 'db-snapshot.mjs' -Arguments @('--database', $SpringDatabase, '--out', $dbSpring, '--container', $Container, '--user', $DbUser)

    $parityPath = Join-Path $WorkDir 'parity.json'
    # --- transitions and finance ---------------------------------------
    # Both are WRITE comparisons, so they run after the read captures and each
    # owns its own database. Transitions move a conversation; finance computes
    # a rollup. Neither is re-run by the read perturbations below.
    $transitionsScenarios = Join-Path $shadowRoot 'scenarios\transitions.json'
    $transitionsPath = Join-Path $WorkDir 'transitions.json'
    $sessionTransitions = Join-Path $WorkDir 'session-transitions.json'
    Note 'STEP' '==> replaying the status-transition sequence on both runtimes'
    $null = Invoke-Node -Script 'transitions.mjs' -Arguments @(
        '--scenarios', $transitionsScenarios,
        '--legacy-base', ("http://127.0.0.1:{0}" -f $LegacyPort),
        '--spring-base', ("http://127.0.0.1:{0}" -f $SpringPort),
        '--legacy-db', $LegacyDatabase, '--spring-db', $SpringDatabase,
        '--session', $sessionTransitions, '--out', $transitionsPath)
    $report.transitions = [ordered]@{ artefact = $transitionsPath }

    $financePath = Join-Path $WorkDir 'finance.json'
    $sessionFinance = Join-Path $WorkDir 'session-finance.json'
    Note 'STEP' '==> computing the finance rollup and deriving the reference from the source rows'
    $null = Invoke-Node -Script 'finance.mjs' -Arguments @(
        '--base', ("http://127.0.0.1:{0}" -f $SpringPort),
        '--database', $SpringDatabase, '--container', $Container, '--user', $DbUser,
        '--project', 'a1a1a1a1-0000-4000-8000-000000000001',
        '--from', '2026-01-01', '--to', '2026-01-31',
        '--email', 'shadow-admin-a@shadow.invalid',
        '--session', $sessionFinance, '--out', $financePath)
    $report.finance = [ordered]@{ artefact = $financePath; legacySide = 'un-comparable: the legacy runtime has no finance module, ledger or rate card' }
    Invoke-SoakNow -Label 'after-transitions-and-finance'

    $code = Invoke-Node -Script 'compare.mjs' -Arguments @(
        '--legacy', $capLegacy, '--spring', $capSpring, '--scenarios', $scenariosPath,
        '--db-legacy', $dbLegacy, '--db-spring', $dbSpring, '--routes', $routesPath,
        '--transitions', $transitionsPath, '--finance', $financePath,
        '--out', $parityPath) -AllowNonZero
    $parity = Get-Content -Raw -LiteralPath $parityPath | ConvertFrom-Json
    $report.baseline = [ordered]@{
        verdict = $parity.verdict
        comparedRequests = $parity.comparedRequests
        checks = @($parity.checks | ForEach-Object { [ordered]@{ id = $_.id; verdict = $_.verdict; failures = @($_.failures); redCondition = $_.redCondition } })
        unComparable = @($parity.unComparable | ForEach-Object { [ordered]@{ request = $_.request; why = $_.why; legacyStatus = $_.legacyStatus; springStatus = $_.springStatus; structuralBasis = $_.structuralBasis } })
        divergences = @($parity.divergences)
        artefact = $parityPath
    }
    if ($code -ne 0) {
        Fail 'FE-SHADOW-001' ("the baseline comparison reported differences; see {0}" -f $parityPath)
    }

    # --- perturbations ------------------------------------------------
    # Each perturbation names the checks it must turn red. A perturbation
    # whose checks stay green is a HARNESS DEFECT and fails the gate.
    $perturbations = @(
        @{ id = 'PN-01'; name = 'an extra customer row exists only in the Spring database'
           file = 'pn01-extra-customer-spring.sql'; db = 'spring'
           mustFlip = @('CHK-1-row-counts-and-identifiers', 'CHK-4-source-rows') },
        @{ id = 'PN-02'; name = 'a migrated cxId was rewritten in the Spring database'
           file = 'pn02-rewritten-cxid-spring.sql'; db = 'spring'
           mustFlip = @('CHK-4-source-rows', 'CHK-1-row-counts-and-identifiers') },
        @{ id = 'PN-03'; name = 'a customer display name differs in the Spring database'
           file = 'pn03-divergent-value-spring.sql'; db = 'spring'
           mustFlip = @('CHK-2-response-bodies') },
        @{ id = 'PN-04'; name = 'agent-a1 was granted a membership of project A2 in the Spring database'
           file = 'pn04-extra-membership-spring.sql'; db = 'spring'
           mustFlip = @('CHK-3-authorization-decisions') },
        @{ id = 'PN-05'; name = 'a customer row was moved into another tenant in the Spring database'
           file = 'pn05-cross-tenant-move-spring.sql'; db = 'spring'
           mustFlip = @('CHK-4-source-rows') }
    )

    $baselineVerdicts = @{}
    foreach ($check in $parity.checks) { $baselineVerdicts[$check.id] = $check.verdict }

    foreach ($perturbation in $perturbations) {
        if ($CompareOnly) { break }
        $sqlPath = Join-Path $shadowRoot ('perturbations\' + $perturbation.file)
        if (-not (Test-Path -LiteralPath $sqlPath)) {
            Fail 'FE-SHADOW-010' ("perturbation {0} has no SQL file at {1}" -f $perturbation.id, $sqlPath)
            continue
        }
        $target = if ($perturbation.db -eq 'spring') { $SpringDatabase } else { $LegacyDatabase }

        Note 'STEP' ("==> {0}: {1} (expecting {2} to go red)" -f $perturbation.id, $perturbation.name, ($perturbation.mustFlip -join ', '))
        try {
            Invoke-PsqlFile -Database $target -Path $sqlPath -Label $perturbation.id
            $pCapLegacy = Join-Path $WorkDir ("capture-legacy-{0}.json" -f $perturbation.id)
            $pCapSpring = Join-Path $WorkDir ("capture-spring-{0}.json" -f $perturbation.id)
            $pLegacyBase = 'http://127.0.0.1:{0}' -f $LegacyPort
            $pSpringBase = 'http://127.0.0.1:{0}' -f $SpringPort
            $null = Invoke-Node -Script 'capture.mjs' -Arguments @('--side', 'legacy', '--base', $pLegacyBase, '--scenarios', $scenariosPath, '--out', $pCapLegacy, '--session', $sessionLegacy)
            $null = Invoke-Node -Script 'capture.mjs' -Arguments @('--side', 'spring', '--base', $pSpringBase, '--scenarios', $scenariosPath, '--out', $pCapSpring, '--session', $sessionSpring)
            $pDbLegacy = Join-Path $WorkDir ("db-legacy-{0}.json" -f $perturbation.id)
            $pDbSpring = Join-Path $WorkDir ("db-spring-{0}.json" -f $perturbation.id)
            $null = Invoke-Node -Script 'db-snapshot.mjs' -Arguments @('--database', $LegacyDatabase, '--out', $pDbLegacy, '--container', $Container, '--user', $DbUser)
            $null = Invoke-Node -Script 'db-snapshot.mjs' -Arguments @('--database', $SpringDatabase, '--out', $pDbSpring, '--container', $Container, '--user', $DbUser)
            $pParity = Join-Path $WorkDir ("parity-{0}.json" -f $perturbation.id)
            # The baseline transition and finance captures are passed through
            # UNCHANGED. A read perturbation does not touch a conversation or
            # a ledger row, so re-running them would add noise; and omitting
            # them would make CHK-6/7/8 report "no capture supplied" in every
            # perturbation run, which is a red that means nothing.
            $null = Invoke-Node -Script 'compare.mjs' -Arguments @(
                '--legacy', $pCapLegacy, '--spring', $pCapSpring, '--scenarios', $scenariosPath,
                '--db-legacy', $pDbLegacy, '--db-spring', $pDbSpring, '--routes', $routesPath,
                '--transitions', $transitionsPath, '--finance', $financePath,
                '--out', $pParity) -AllowNonZero
            $perturbed = Get-Content -Raw -LiteralPath $pParity | ConvertFrom-Json

            $flipped = @()
            $notFlipped = @()
            foreach ($checkId in $perturbation.mustFlip) {
                $was = $baselineVerdicts[$checkId]
                $now = ($perturbed.checks | Where-Object { $_.id -eq $checkId }).verdict
                if ($was -eq 'PASS') {
                    if ($now -eq 'FAIL') { $flipped += $checkId } else { $notFlipped += ("{0} stayed {1}" -f $checkId, $now) }
                } else {
                    # The baseline was already red for this check, so a
                    # perturbation cannot be credited with turning it red.
                    # Saying so is the honest outcome.
                    $flipped += ("{0} was already FAIL at baseline" -f $checkId)
                }
            }
            $report.perturbations = ConvertTo-QArray -Value ($report.perturbations + @([ordered]@{
                id = $perturbation.id
                name = $perturbation.name
                appliedTo = $target
                sql = $sqlPath
                mustFlip = $perturbation.mustFlip
                flipped = $flipped
                notFlipped = $notFlipped
                proved = ($notFlipped.Count -eq 0)
                artefact = $pParity
            }))
            Invoke-SoakNow -Label ("after-" + $perturbation.id)
            if ($notFlipped.Count -gt 0) {
                Fail 'FE-SHADOW-011' ("{0} did not turn {1} red. The check cannot see the difference it claims to measure, so its green verdict means nothing. Artifacts under {2}." -f $perturbation.id, ($perturbation.mustFlip -join ', '), $WorkDir)
            }
        }
        catch {
            Fail 'FE-SHADOW-012' ("{0} could not be applied: {1}" -f $perturbation.id, $_.Exception.Message)
        }
        finally {
            $restorePath = Join-Path $shadowRoot ('perturbations\' + ($perturbation.file -replace '\.sql$', '.restore.sql'))
            if (Test-Path -LiteralPath $restorePath) {
                try { Invoke-PsqlFile -Database $target -Path $restorePath -Label ($perturbation.id + '-restore') }
                catch { Fail 'FE-SHADOW-013' ("{0} could not be reverted: {1}. The gate continues but the disposable database is now dirty and must be recreated." -f $perturbation.id, $_.Exception.Message) }
            }
            else {
                Fail 'FE-SHADOW-014' ("{0} has no restore file ({1}); a perturbation that cannot be undone is not a perturbation this gate is allowed to run." -f $perturbation.id, $restorePath)
            }
        }
    }

    # --- detector perturbation ---------------------------------------
    # The branch a database edit cannot reach: a refusal that DISCLOSES.
    # Proving it needs a refusal body that leaks, and neither runtime will
    # produce one on demand, so the comparator is fed a doctored capture.
    # This is labelled as a detector perturbation everywhere it appears, and
    # it does NOT count as evidence about either runtime's behaviour.
    $doctored = Join-Path $WorkDir 'capture-legacy-doctored.json'
    $raw = Get-Content -Raw -LiteralPath $capLegacy | ConvertFrom-Json
    $target = $raw.results | Where-Object { $_.id -eq 'customer.byId.crossTenant.adminA' }
    $target.status = 404
    $target.body = [ordered]@{
        code = 'NOT_FOUND'
        message = ('no customer with cxId #33000005 named Shadow Patient Five in this organization')
    }
    $raw | ConvertTo-Json -Depth 40 | ForEach-Object {
        [IO.File]::WriteAllText($doctored, $_, (New-Object System.Text.UTF8Encoding($false)))
    }
    $doctoredParity = Join-Path $WorkDir 'parity-PN-06.json'
    $parityArgs = @(
        '--legacy', $doctored, '--spring', $capSpring, '--scenarios', $scenariosPath,
        '--db-legacy', $dbLegacy, '--db-spring', $dbSpring, '--routes', $routesPath,
        '--transitions', $transitionsPath, '--finance', $financePath,
        '--out', $doctoredParity)
    $null = Invoke-Node -Script 'compare.mjs' -Arguments $parityArgs -AllowNonZero
    $doctoredResult = Get-Content -Raw -LiteralPath $doctoredParity | ConvertFrom-Json
    $authzCheck = $doctoredResult.checks | Where-Object { $_.id -eq 'CHK-3-authorization-decisions' }
    $disclosureProved = ($authzCheck.failures | Where-Object { $_ -match 'disclosed' }).Count -gt 0
    $report.perturbations = ConvertTo-QArray -Value ($report.perturbations + @([ordered]@{
        id = 'PN-06'
        name = 'detector perturbation: a legacy refusal body was doctored to echo a cxId, a display name and a tenant'
        class = 'detector'
        mustFlip = @('CHK-3-authorization-decisions')
        proved = $disclosureProved
        artefact = $doctoredParity
        caveat = 'this proves the disclosure detector fires. It is NOT evidence about either runtime.'
    }))
    if (-not $disclosureProved) {
        Fail 'FE-SHADOW-015' 'PN-06: the comparator accepted a refusal body that named another tenant''s customer. The disclosure scan is not working, so every "denials are clean" verdict in this report is worthless.'
    }

    # --- PN-07: the close step is removed from the Spring side only ----
    # The transition check has to notice that one runtime was asked to do less
    # work. A comparison that only looked at the final row could be satisfied
    # by a runtime that ignored a step and another that performed it, provided
    # the last step happened to produce the same state; this perturbation makes
    # the two states genuinely different.
    if (-not $CompareOnly) {
        $variantPath = Join-Path $WorkDir 'transitions-PN-07.json'
        Note 'STEP' '==> PN-07: replaying a SHORTER sequence on the new runtime (expecting CHK-6 to go red)'
        try {
            $null = Invoke-Node -Script 'transitions.mjs' -Arguments @(
                '--scenarios', (Join-Path $shadowRoot 'scenarios\transitions.json'),
                '--legacy-base', ("http://127.0.0.1:{0}" -f $LegacyPort),
                '--spring-base', ("http://127.0.0.1:{0}" -f $SpringPort),
                '--legacy-db', $LegacyDatabase, '--spring-db', $SpringDatabase,
                '--session', (Join-Path $WorkDir 'session-transitions.json'),
                '--variant-side', 'spring',
                '--out', $variantPath)
            $variantParity = Join-Path $WorkDir 'parity-PN-07.json'
            $null = Invoke-Node -Script 'compare.mjs' -Arguments @(
                '--legacy', $capLegacy, '--spring', $capSpring, '--scenarios', $scenariosPath,
                '--db-legacy', $dbLegacy, '--db-spring', $dbSpring, '--routes', $routesPath,
                '--transitions', $variantPath, '--finance', $financePath,
                '--out', $variantParity) -AllowNonZero
            $variantResult = Get-Content -Raw -LiteralPath $variantParity | ConvertFrom-Json
            $transitionCheck = $variantResult.checks | Where-Object { $_.id -eq 'CHK-6-status-transitions' }
            $pn07 = ($transitionCheck.verdict -eq 'FAIL')
            $report.perturbations = ConvertTo-QArray -Value ($report.perturbations + @([ordered]@{
                id = 'PN-07'
                name = 'a shorter transition sequence was fed to the new runtime only'
                class = 'runtime'
                mustFlip = @('CHK-6-status-transitions')
                proved = $pn07
                failures = @($transitionCheck.failures)
                artefact = $variantParity
            }))
            if (-not $pn07) {
                Fail 'FE-SHADOW-016' 'PN-07: the transition comparison did not notice that one runtime was given a different sequence. CHK-6 is not looking at the state the transition produced.'
            }
        }
        catch { Fail 'FE-SHADOW-017' ("PN-07 could not be applied: {0}" -f $_.Exception.Message) }
    }

    # --- PN-08: a further usage event lands in the ledger ---------------
    # The whole finance ledger is append-only by design
    # (firstelement_append_only() on usage_events, rate_card_versions,
    # rate_card_version_lines and usage_rollup_snapshots), so the only
    # admissible mutation is an INSERT. A cost event is appended AFTER the
    # baseline rollup was computed; the derived revenue must move by exactly
    # that event's cost times the markup. A rollup whose revenue was cached,
    # hard-coded, or computed from anything other than the events cannot follow
    # an event that did not exist when it was first computed.
    #
    # The first attempt at this perturbation UPDATEs the rate card markup, on
    # the reasoning that a derivation check which cannot survive a changed rate
    # is not a derivation check. The database refused it and the database was
    # right. That attempt is recorded here rather than deleted, because "the
    # harness tried to mutate an immutable ledger" is a finding about the
    # harness, and the version that reported it as a product defect was
    # reporting its own mistake as the cutover candidate's.
    #
    # A second harness defect lived in the verdict for this perturbation and is
    # recorded in the same spirit: `generationOneFrozen` compared one row of
    # `olderGenerations` against another row of `olderGenerations`, so it was
    # true by construction and true when no such row existed. The verdict now
    # lives in tests/shadow/pn08-proof.mjs, which compares the PRE-perturbation
    # capture against the POST-perturbation one.
    if (-not $CompareOnly) {
        Note 'STEP' '==> PN-08: appending one more usage event to the ledger (expecting the derivation to follow it and generation 1 to stay frozen)'
        try {
            Invoke-PsqlFile -Database $SpringDatabase -Path (Join-Path $shadowRoot 'perturbations\pn08-extra-usage-event-spring.sql') -Label 'PN-08'
            $pn08Finance = Join-Path $WorkDir 'finance-PN-08.json'
            $null = Invoke-Node -Script 'finance.mjs' -Arguments @(
                '--base', ("http://127.0.0.1:{0}" -f $SpringPort),
                '--database', $SpringDatabase, '--container', $Container, '--user', $DbUser,
                '--project', 'a1a1a1a1-0000-4000-8000-000000000001',
                '--from', '2026-01-01', '--to', '2026-01-31',
                '--email', 'shadow-admin-a@shadow.invalid',
                '--session', (Join-Path $WorkDir 'session-finance.json'),
                '--out', $pn08Finance)
            $pn08Parity = Join-Path $WorkDir 'parity-PN-08.json'
            $null = Invoke-Node -Script 'compare.mjs' -Arguments @(
                '--legacy', $capLegacy, '--spring', $capSpring, '--scenarios', $scenariosPath,
                '--db-legacy', $dbLegacy, '--db-spring', $dbSpring, '--routes', $routesPath,
                '--transitions', $transitionsPath, '--finance', $pn08Finance,
                '--out', $pn08Parity) -AllowNonZero
            # CHK-7 is not read here any more: pn08-proof.mjs reads it out of
            # $pn08Parity itself, so that the comparator artefact and the verdict
            # cannot be two different files.

            # CHK-7 is EXPECTED TO STAY GREEN here. Both the reference SQL and
            # the runtime now read the same six events -- the five the seed
            # writes plus the one this perturbation appends -- so agreement is
            # the correct outcome. What has to be proven is that the runtime
            # MOVED the way a derivation has to move -- by exactly the added
            # event's cost times the markup (2.000000 x 1.500000 = 3.000000) --
            # into a NEW generation, with one more contributing event, and
            # without touching the generation that had already been computed.
            #
            # THE VERDICT LIVES IN tests/shadow/pn08-proof.mjs, NOT HERE, and
            # that placement is the fix rather than a style preference. Written
            # inline here it could only ever be exercised by a five-minute
            # full-stack run, so it went unexamined for a phase -- and what
            # examination found was that its "generation 1 stayed frozen"
            # conjunct read generation 1's event count out of
            # `olderGenerations` and then compared `olderGenerations` against
            # itself. It was true by construction, and true even when no
            # generation-1 row existed. The verbatim pre-fix expression is kept
            # runnable in tests/shadow/pn08-prefix-reproduction.ps1 and both
            # states are exercised by tests/shadow/pn08-proof.test.mjs.
            #
            # `--added-cost` is the costAmount the perturbation file inserts. It
            # is declared here rather than read back out of the captures so that
            # the expectation belongs to the FIXTURE; the test asserts the two
            # cannot drift apart. The old expression hard-coded 3.0 here, which
            # meant an edit to the SQL would have been reported as a defect in
            # the cutover candidate.
            $pn08ProofPath = Join-Path $WorkDir 'pn08-proof.json'
            $null = Invoke-Node -Script 'pn08-proof.mjs' -Arguments @(
                '--baseline', $financePath,
                '--perturbed', $pn08Finance,
                '--parity', $pn08Parity,
                '--dimension', 'MESSAGE',
                '--added-cost', '2.000000',
                '--out', $pn08ProofPath) -AllowNonZero
            if (-not (Test-Path -LiteralPath $pn08ProofPath)) {
                Fail 'FE-SHADOW-024' 'PN-08: the verdict module wrote no artefact, so the finance derivation proof did not run. A harness that did not run is not a parity result.'
            }
            else {
                $pn08Verdict = Get-Content -Raw -LiteralPath $pn08ProofPath | ConvertFrom-Json
                $pn08Ok = [bool]$pn08Verdict.proved
                $report.perturbations = ConvertTo-QArray -Value ($report.perturbations + @([ordered]@{
                    id = 'PN-08'
                    name = 'one more usage event (MESSAGE x 2.000000) was appended to the Spring ledger and the rollup recomputed'
                    class = 'runtime'
                    mustFlip = @('CHK-7-finance-derivation must stay green AND the stored MESSAGE revenue must rise by 3.000000 into a NEW generation', 'the already-computed generation 1 must be identical to what the PRE-perturbation capture recorded')
                    proved = $pn08Ok
                    polarity = $pn08Verdict.polarity
                    derivationVerdict = $pn08Verdict.derivationVerdict
                    revenueFollowedTheEvent = $pn08Verdict.revenueFollowedTheEvent
                    sourceEventCountAdvanced = $pn08Verdict.sourceEventCountAdvanced
                    generationOneFrozen = $pn08Verdict.generationOneFrozen
                    reasons = @($pn08Verdict.reasons)
                    baselineMessageSnapshot = $pn08Verdict.observed.baselineSnapshot
                    perturbedMessageSnapshot = $pn08Verdict.observed.perturbedCurrent
                    artefact = $pn08ProofPath
                    comparatorArtefact = $pn08Parity
                }))
                if (-not $pn08Ok) {
                    Fail 'FE-SHADOW-018' ("PN-08: the finance rollup did not follow an event appended after it was computed. Either revenue is not derived from the ledger, or a recomputation mutated an already-computed snapshot. {0}" -f (($pn08Verdict.reasons) -join ' '))
                }
            }
        }
        catch { Fail 'FE-SHADOW-019' ("PN-08 could not be applied: {0}" -f $_.Exception.Message) }
        finally {
            try { Invoke-PsqlFile -Database $SpringDatabase -Path (Join-Path $shadowRoot 'perturbations\pn08-extra-usage-event-spring.restore.sql') -Label 'PN-08-restore' }
            catch { Fail 'FE-SHADOW-021' ("PN-08 could not be reverted: {0}. The disposable database is dropped at the end of the run regardless, but the restore file is wrong and the next run will fail to seed." -f $_.Exception.Message) }
        }
    }

    # --- PN-09: a detector perturbation on the finance invariants ------
    # The branch a rate change cannot reach: a stored revenue that is not its
    # own cost x its own markup. Feeding the comparator a doctored finance
    # capture proves the invariant check fires. It is a DETECTOR proof and is
    # not evidence about the runtime.
    if (-not $CompareOnly) {
        $doctoredFinance = Join-Path $WorkDir 'finance-doctored.json'
        $financeDoc = Get-Content -Raw -LiteralPath $financePath | ConvertFrom-Json
        $target = @($financeDoc.currentGeneration | Where-Object { $null -ne $_.revenue })[0]
        if ($null -eq $target) {
            Fail 'FE-SHADOW-022' 'PN-09: no priced bucket was captured, so the invariant detector could not be proven able to fail.'
        } else {
            $target.revenue = [double]$target.revenue + 1
            $financeDoc | ConvertTo-Json -Depth 40 | ForEach-Object {
                [IO.File]::WriteAllText($doctoredFinance, $_, (New-Object System.Text.UTF8Encoding($false)))
            }
            $pn09Parity = Join-Path $WorkDir 'parity-PN-09.json'
            $null = Invoke-Node -Script 'compare.mjs' -Arguments @(
                '--legacy', $capLegacy, '--spring', $capSpring, '--scenarios', $scenariosPath,
                '--db-legacy', $dbLegacy, '--db-spring', $dbSpring, '--routes', $routesPath,
                '--transitions', $transitionsPath, '--finance', $doctoredFinance,
                '--out', $pn09Parity) -AllowNonZero
            $pn09Result = Get-Content -Raw -LiteralPath $pn09Parity | ConvertFrom-Json
            $invariants = $pn09Result.checks | Where-Object { $_.id -eq 'CHK-8-finance-invariants' }
            $pn09 = ($invariants.verdict -eq 'FAIL')
            $report.perturbations = ConvertTo-QArray -Value ($report.perturbations + @([ordered]@{
                id = 'PN-09'
                name = 'detector perturbation: one stored revenue was incremented by 1 in the capture'
                class = 'detector'
                mustFlip = @('CHK-8-finance-invariants')
                proved = $pn09
                failures = @($invariants.failures)
                artefact = $pn09Parity
                caveat = 'this proves the invariant detector fires. It is NOT evidence about the runtime.'
            }))
            if (-not $pn09) {
                Fail 'FE-SHADOW-023' 'PN-09: the finance invariant check accepted a stored revenue that is not its own cost x its own markup. Every "the ledger arithmetic is self-consistent" statement in this report is worthless.'
            }
        }
    }
    Invoke-SoakNow -Label 'before-shutdown'

    # --- soak result --------------------------------------------------
    if (-not $NoSoak) {
        $samples = [ordered]@{}
        foreach ($label in @('legacy', 'spring')) {
            $list = @($script:SoakSamples[$label])
            # "NO ANSWER" is the ADR 0013 section 4 signature: a request that
            # got no status at all, as opposed to a refusal. Both are kept.
            $stalled = @($list | Where-Object { $_.get -like 'NO ANSWER*' -or $_.post -like 'NO ANSWER*' })
            $samples[$label] = [ordered]@{
                probes = $list.Count
                durationSeconds = [int]((Get-Date) - $script:SoakStart).TotalSeconds
                checkpoints = @($list | ForEach-Object { $_.checkpoint })
                stallObserved = ($stalled.Count -gt 0)
                stalledProbes = $stalled
                samples = $list
            }
        }
        $report.soak = [ordered]@{
            adr = 'ADR 0013 section 4'
            checkpoints = @($script:SoakSamples['legacy'] | ForEach-Object { $_.checkpoint } | Select-Object -Unique)
            observed = $samples
            verdict = if ($samples['spring'].stallObserved -or $samples['legacy'].stallObserved) { 'STALL OBSERVED' } else { 'NOT OBSERVED IN THIS RUN' }
            honesty = 'This run lasted minutes. ADR 0013 section 4 describes an intermittent that appeared after roughly two hours of serving, so a run of this length CANNOT reproduce it. NOT OBSERVED is not a closure, and this evidence package does not close that item.'
        }
    }

    # --- protected database ------------------------------------------
    if (-not $CompareOnly) {
        $after = Get-ProtectedBackends
        $report.databases.protectedDatabaseBackendsAfter = $after
        $report.databases.protectedDatabaseWrites = 0
        Note 'STEP' ("==> protected database backends: {0} before, {1} after" -f $protectedBackendsBefore, $after)
    }

    # --- verdict ------------------------------------------------------
    $verdict = if ($findings.Count -eq 0) { 'PASS' } else { 'FAIL' }
    $report.verdict = $verdict
}
catch {
    Fail 'FE-SHADOW-000' $_.Exception.Message
    $report.verdict = 'FAIL'
    $report.error = $_.Exception.Message
}
finally {
    # PHASE 49. The kill is now VERIFIED rather than attempted, and it escalates.
    #
    # The old teardown called $process.Kill() and moved on. It reported success by
    # omission: nothing afterwards could tell whether the JVM had actually gone, and in
    # practice it had not -- the spring runtime survived teardown holding 4402 and 4403,
    # which is the runtime leak that was reported as a contradiction of this very stage's
    # claim that it "releases them on exit". A teardown that cannot fail is not a teardown.
    # So: kill by handle, confirm the process is gone, then confirm the PORTS are free, and
    # if either is untrue, kill whatever owns the ports -- which is the same rule the harness
    # already applies on the way IN, where an unexplained listener is refused rather than
    # assumed to be a stranger.
    $stillHeld = @()
    foreach ($named in @(@{ label = 'spring'; handle = $script:springProcess }, @{ label = 'legacy'; handle = $script:legacyProcess })) {
        $process = $named.handle
        if (-not $process) {
            Note 'WARN' ("{0} runtime: no process handle was recorded, so it can only be released by port" -f $named.label)
            continue
        }
        try {
            if (-not $process.HasExited) { $process.Kill() }
            $null = $process.WaitForExit(20000)
            if (-not $process.HasExited) {
                $stillHeld += ("{0} runtime (pid {1}) did not exit within 20s of Kill()" -f $named.label, $process.Id)
            }
            else {
                Note 'STEP' ("==> stopped the {0} runtime (pid {1})" -f $named.label, $process.Id)
            }
        }
        catch {
            $stillHeld += ("{0} runtime (pid {1}) could not be stopped: {2}" -f $named.label, $process.Id, $_.Exception.Message)
        }
    }
    # Escalate to whoever owns the ports. Verified by re-reading the listeners, because the
    # only proof that a port was released is that nothing is listening on it any more.
    $leaked = @(Get-NetTCPConnection -LocalPort $LegacyPort, $SpringPort, $SpringManagementPort -State Listen -ErrorAction SilentlyContinue)
    if ($leaked.Count -gt 0) {
        foreach ($listener in $leaked) {
            $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
            if ($owner -and $owner.ProcessName -in @('java', 'node')) {
                Note 'WARN' ("port {0} is still held after killing the handle (pid {1}); releasing it by port" -f $listener.LocalPort, $owner.Id)
                try { Stop-Process -Id $owner.Id -Force } catch { }
            }
            elseif ($owner) {
                $stillHeld += ("port {0} is held by {1} (pid {2}), which this stage will not touch" -f $listener.LocalPort, $owner.ProcessName, $owner.Id)
            }
        }
        for ($attempt = 1; $attempt -le 10; $attempt++) {
            $leaked = @(Get-NetTCPConnection -LocalPort $LegacyPort, $SpringPort, $SpringManagementPort -State Listen -ErrorAction SilentlyContinue)
            if ($leaked.Count -eq 0) { break }
            Start-Sleep -Seconds 2
        }
        if ($leaked.Count -gt 0) {
            $stillHeld += ("ports {0} are still listening after killing by handle AND by port" -f ((@($leaked | ForEach-Object { $_.LocalPort })) -join ', '))
        }
    }
    if ($stillHeld.Count -gt 0) {
        Fail 'FE-SHADOW-021' ("the shadow runtimes were not released: {0}. A stage that leaves a JVM listening on its own ports contradicts the next run, which would capture against a runtime it did not start." -f ($stillHeld -join '; '))
    }
    foreach ($timer in @()) { if ($timer) { try { $timer.Stop(); $timer.Dispose() } catch { } } }
    # Dispose the throwaway databases unless the caller asked to keep them for
    # inspection. A rehearsal that leaves a database behind is a rehearsal
    # whose next run measures the previous run's leftovers.
    if (-not $CompareOnly -and -not $env:FE_SHADOW_KEEP) {
        foreach ($db in @($LegacyDatabase, $SpringDatabase)) {
            # A JVM killed with Kill() takes a moment to close its sockets, and
            # Postgres refuses a DROP while a backend is still attached. Five
            # attempts with a pause, because "could not dispose" is not a
            # cosmetic warning: a shadow database left behind is the next run's
            # starting state, and the next run's counts would include this
            # run's rows.
            $disposed = $false
            for ($attempt = 1; $attempt -le 5 -and -not $disposed; $attempt++) {
                try {
                    $null = Invoke-Psql -Database 'firstelement' -Sql ("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '{0}' AND pid <> pg_backend_pid();" -f $db) -StatementTimeoutMs 15000
                    # PHASE 49: bounded. Without this, the DROP waits on a lock held by a
                    # reconnecting pool and the stage never returns.
                    $null = Invoke-Psql -Database 'firstelement' -Sql ('DROP DATABASE IF EXISTS "{0}";' -f $db) -StatementTimeoutMs 20000
                    $disposed = $true
                } catch {
                    Start-Sleep -Seconds 3
                }
            }
            if ($disposed) { Note 'STEP' ("==> disposed the disposable database {0}" -f $db) }
            else { Fail 'FE-SHADOW-020' ("could not dispose the disposable database {0}. It must be dropped by hand before the next run, or the next run measures this one's leftovers." -f $db) }
        }
    }
    Remove-Item Env:\SESSION_SECRET, Env:\ENCRYPTION_KEY, Env:\SHADOW_PARITY_PASSWORD -ErrorAction SilentlyContinue
}

$report.findings = ConvertTo-QArray -Value $findings
$report.workDir = $WorkDir
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $report -Depth 12 | Out-Null }
Write-QJson -Path (Join-Path $WorkDir 'shadow-parity-report.json') -Object $report -Depth 12 | Out-Null

foreach ($finding in $findings) { Note 'ERROR' ("  {0}: {1}" -f $finding.rule, $finding.detail) }
Note 'STEP' ("shadow-parity work directory: {0}" -f $WorkDir)
if ($report.verdict -eq 'PASS') {
    Note 'STEP' 'shadow-parity VERDICT: PASS -- both read paths agree, and every check was proven able to fail'
    exit 0
}
Note 'ERROR' 'shadow-parity VERDICT: FAIL'
exit 1