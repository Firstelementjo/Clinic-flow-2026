# =====================================================================
# ops/quality/checks/run-performance-smoke.ps1
# Drives the performance smoke end to end, because the stage cannot.
# =====================================================================
# WHY THIS EXISTS
# ---------------------------------------------------------------------
# The `performance-smoke` stage invokes tests/performance/run-smoke.ps1 with
# no -TargetBaseUrl and no -SessionCookie, so it could never pass. The
# harness is RIGHT to refuse -- it says so in its own error, and refuses to
# invent a target because "silently starting nothing and reporting a number
# is exactly the failure mode this harness exists to prevent".
#
# What was missing was the ORCHESTRATION, not the harness and not the
# dataset. Both of those are real and were measured on 2026-10-04. This
# script supplies the five steps between them:
#
#   1. create a DISPOSABLE database, empty, with the Flyway chain applied
#   2. boot the application against it, on ports chosen to avoid the
#      shadow-parity harness's 4401-4403
#   3. load tests/performance/dataset.sql
#   4. sign in as the seeded perf identity and take its session cookie
#   5. run the smoke, then tear the database down whatever happened
#
# THE CREDENTIAL, AND WHY IT IS SAFE HERE
# ---------------------------------------------------------------------
# dataset.sql used to write a deliberately INVALID bcrypt hash, so that
# "nobody authenticates as a perf user on purpose". That was right while
# nothing could sign in, and it made the stage unwirable: the harness needs
# a session and the dataset deliberately refused to provide one.
#
# The tension is that a 40,000-customer / 400,000-message dataset with a
# working credential sitting on disk is a liability, and the original
# property was a good instinct. So the resolution is NOT to weaken it
# globally -- it is to make the credential's LIFETIME the thing that is
# safe:
#
#   * the password is generated per run and passed through the environment,
#     never written to the repository, never printed, and never echoed by
#     this script;
#   * it authenticates ONLY against a database this script creates;
#   * that database is DROPPED in a finally block, so the credential stops
#     existing the moment the run ends -- including on failure;
#   * the account is the seeded dataset admin, which exists in no other
#     database;
#   * and the harness refuses to run against anything but a disposable
#     database, which is asserted below rather than assumed.
#
# So the property that mattered -- no usable credential survives a run --
# is now enforced by teardown instead of by making the hash invalid, and
# it holds even when the run fails part-way. Bcrypt has no plaintext
# anywhere: the seed writes the HASH and this script holds the plaintext
# only in memory.
#
# Exit codes: 0 the smoke met its budgets, 1 it did not or the run could
# not be completed, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $Container = 'firstelement-postgres',
    # Named to match run-smoke.ps1's own -TelemetryDatabase default so the two
    # cannot disagree about which database is being read.
    [string] $Database = 'firstelement_perf_gate_check',
    [string] $DbUser = 'firstelement',
    [int] $ApiPort = 4501,
    [int] $ManagementPort = 4502,
    [string] $Report,
    [string] $EvidenceDir,
    [string] $Thresholds,
    [switch] $KeepDatabase
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $Report) { $Report = Join-Path $RepoRoot 'build\performance\performance.json' }
if (-not $EvidenceDir) { $EvidenceDir = Split-Path -Parent $Report }

# ---------------------------------------------------------------------
# The refusal that makes the rest safe.
# ---------------------------------------------------------------------
# Asserted, not assumed. Everything below -- generating a credential, writing
# a quarter of a million rows, booting an application -- is only acceptable
# against a database this script is certain it created.
$protected = @('firstelement', 'postgres', 'template0', 'template1')
if ($protected -contains $Database.ToLowerInvariant()) {
    throw ("Refusing to run: '{0}' is on the protected list. This script writes a quarter of a " +
        "million rows and boots an application; it only ever does that to a database it created " +
        "itself, and '{0}' is not one.") -f $Database
}
if ($Database -notmatch 'perf_gate_check|_perf_') {
    throw ("Refusing to run: the database name '{0}' does not look like a disposable performance " +
        "database. Name it so that it contains 'perf_gate_check' or '_perf_'.") -f $Database
}

# Port and log-path guards, added after several attempts at this stage overwrote each other's
# evidence and left an orphan JVM holding the port. Two runs sharing 4501/4502 means the second
# app cannot bind, the wrapper sees the first app as "exited", and BOTH write to the same
# perf-app.out -- so the log being read belongs to a run that is not the one being diagnosed.
# That ambiguity is exactly what this harness exists to remove, so it is refused rather than
# worked around.
foreach ($port in @($ApiPort, $ManagementPort)) {
    $busy = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    if ($busy.Count -gt 0) {
        throw ("Refusing to run: port {0} is already in use by pid {1}. A previous performance run " +
            "either outlived itself or is still going. Clear that before starting another.") -f `
            $port, (($busy | ForEach-Object { $_.OwningProcess }) -join ',')
    }
}

# A per-run log name, so two runs can never stomp each other's evidence again.
# The directory is created here: Start-Process will not create intermediate directories for a
# redirect target, and a missing directory makes it fail in a way that has nothing to do with the
# application -- which is how an earlier attempt lost its own boot log entirely.
if (-not (Test-Path -LiteralPath $EvidenceDir)) {
    New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
}
$runStamp = Get-Date -Format 'yyyyMMddTHHmmssZ'
$appOut = Join-Path $EvidenceDir "perf-app-$runStamp.out"
$appErr = Join-Path $EvidenceDir "perf-app-$runStamp.err"

$datasetSql  = Join-Path $RepoRoot 'tests\performance\dataset.sql'
# PHASE 47. Overridable so ONE scenario can be run in isolation. A full smoke costs six minutes,
# and a hypothesis about a single scenario should not have to buy the other six to be tested.
$thresholds  = if ($Thresholds) { $Thresholds } else { Join-Path $RepoRoot 'tests\performance\thresholds.json' }
$smokeScript = Join-Path $RepoRoot 'tests\performance\run-smoke.ps1'
$jar         = Join-Path $RepoRoot 'backend\app-bootstrap\build\libs\firstelement-api-0.1.0.jar'

foreach ($required in @($datasetSql, $thresholds, $smokeScript)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing $required" }
}
if (-not (Test-Path -LiteralPath $jar)) {
    throw ("Missing $jar. Build it first: gradlew :backend:app-bootstrap:bootJar. This script does " +
        "not build, because a gate that builds its own subject can report green about code it " +
        "compiled rather than code it was given.")
}

function Invoke-Db {
    param([string] $Sql, [string] $Db = $Database, [switch] $AllowNonZero)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db -v ON_ERROR_STOP=1 -t -A -f - 2>&1) |
        ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $previous
    if ($LASTEXITCODE -ne 0 -and -not $AllowNonZero) {
        throw "psql failed against $Db :`n$($out -join "`n")"
    }
    return $out
}

# A password that satisfies the app's own login validation without being memorable.
# The login record is `@NotBlank @Size(max = 200)` -- there is no minimum and no character-class
# rule -- so length is the only constraint that matters.
#
# RNGCryptoServiceProvider rather than RandomNumberGenerator: the latter does not resolve under
# Windows PowerShell 5.1, which is what the gate runner uses. The classic .NET Framework name does.
function New-ThrowawayPassword {
    $alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#%*'
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    try {
        $bytes = New-Object byte[] 28
        $rng.GetBytes($bytes)
        $chars = foreach ($b in $bytes) { $alphabet[$b % $alphabet.Length] }
        return -join $chars
    }
    finally { $rng.Dispose() }
}

$perfPassword = New-ThrowawayPassword

function Get-BcryptHash12 {
    param([Parameter(Mandatory = $true)][string] $Plaintext)
    # bcryptjs at cost 12, because PasswordVerifier documents that it expects
    # "$2a$12$..." -- the same library and the same cost the legacy hashes use.
    # The plaintext is handed to node on STDIN so it appears in no argv, no command line and
    # no process listing; only the hash comes back.
    $nodeScript = "const b=require('bcryptjs');let d='';process.stdin.on('data',c=>d+=c);" +
                  "process.stdin.on('end',()=>process.stdout.write(b.hashSync(d.trim(),12)));"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'node'
    $psi.Arguments = '-e "' + ($nodeScript -replace '"', '\"') + '"'
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.WorkingDirectory = $RepoRoot
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.Write($Plaintext)
    $proc.StandardInput.Close()
    $hash = $proc.StandardOutput.ReadToEnd().Trim()
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0 -or $hash -notmatch '^\$2a\$12\$') {
        throw ("bcryptjs did not produce a `$2a`$12` hash (exit $($proc.ExitCode)): $err")
    }
    return $hash
}

# The seeded administrator. dataset.sql creates six users per org named
# perf-user-<org slug>-<n>@perf.invalid, so this is the first of org 1.
$perfEmail = 'perf-user-perf-org-1-1@perf.invalid'

$appProcess = $null
$exit = 1
try {
    # The hash is computed ONCE, before the dataset is read, and only the hash is
    # substituted. The plaintext never enters the SQL that reaches psql.
    $perfHash = Get-BcryptHash12 -Plaintext $perfPassword
    # --- 1. an empty, disposable database; the application migrates it ---
    Write-QLog "STEP 1/5  recreating $Database (empty; the application applies the Flyway chain on boot)"
    & (Join-Path $RepoRoot 'ops\quality\checks\prepare-throwaway-db.ps1') `
        -Container $Container -Database $Database -DbUser $DbUser | Write-Host

    # --- 2. boot the application on ports clear of the shadow harness ---
    Write-QLog "STEP 2/5  booting the application on $ApiPort (management $ManagementPort)"
    $appEnv = @{
        SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/$Database"
        SERVER_PORT           = "$ApiPort"
        MANAGEMENT_PORT       = "$ManagementPort"
    }
    $previousItems = @{}
    foreach ($k in $appEnv.Keys) { $previousItems[$k] = [Environment]::GetEnvironmentVariable($k) }
    foreach ($k in $appEnv.Keys) { [Environment]::SetEnvironmentVariable($k, $appEnv[$k]) }
    try {
        $appProcess = Start-Process -FilePath 'java' `
            -ArgumentList @(
                '-Djava.net.preferIPv4Stack=true',
                '-jar', $jar,
                "--server.port=$ApiPort",
                "--management.server.port=$ManagementPort"
            ) `
            -WorkingDirectory $RepoRoot `
            -RedirectStandardOutput $appOut `
            -RedirectStandardError $appErr `
            -WindowStyle Hidden -PassThru
    }
    finally {
        foreach ($k in $appEnv.Keys) { [Environment]::SetEnvironmentVariable($k, $previousItems[$k]) }
    }

    # Readiness is TWO conditions, and the second one is the one that matters.
    #
    # The first attempt at this waited only for GET /api/v1/status to answer 200, and loaded the
    # dataset three seconds later into a database whose schema did not exist yet:
    # `ERROR: relation "organizations" does not exist`. A status endpoint is a liveness signal --
    # it answers before the Flyway chain has finished, because the chain is not what it measures.
    #
    # So: the port must be open AND the chain must be applied. The expected count is read from
    # ops/flyway/expected-state.json rather than hard-coded, so a new migration does not silently
    # make this wait for a number that will never arrive.
    $expectedState = Join-Path $RepoRoot 'ops\flyway\expected-state.json'
    $expectedApplied = 0
    if (Test-Path -LiteralPath $expectedState) {
        $expectedApplied = [int](Get-Content -Raw -LiteralPath $expectedState |
            ConvertFrom-Json).chain.migrationCount
    }
    else {
        throw ("Missing $expectedState, so this script cannot tell when the chain has finished " +
            "applying. Refusing to guess: a wrong number here either loads the dataset too early " +
            "or waits forever.")
    }

    $deadline = (Get-Date).AddMinutes(8)
    $portOpen = $false
    $migrated = $false
    $lastReadinessError = $null
    while ((Get-Date) -lt $deadline) {
        if ($appProcess.HasExited) {
            throw ("the application exited with code $($appProcess.ExitCode) during startup; see " +
                $appErr)
        }
        if (-not $portOpen) {
            try {
                $probe = Invoke-WebRequest -Uri "http://localhost:$ApiPort/api/v1/status" `
                    -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
                if ($probe.StatusCode -eq 200) { $portOpen = $true }
            }
            catch { }
        }
        if ($portOpen) {
            try {
                # One text column with an unambiguous value.
                #
                # Two earlier attempts at this were wrong in ways that could never succeed:
                #   `bool AND count::text`  -- no `boolean AND text` operator exists, and the
                #                            readiness loop swallowed the error, so the run spun
                #                            to its deadline and blamed the chain instead;
                #   `(... )::text = 't'`    -- `::text` on a boolean renders `true`, while `t` is
                #                            what psql prints for a NATIVE boolean under -t -A.
                #                            So it compared 'true' against 't' for eight minutes.
                # CASE yields a named value that cannot be misread either way.
                $applied = @(Invoke-Db -Sql @"
SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NOT NULL
             AND (SELECT count(*) FROM public.flyway_schema_history WHERE "success") = $expectedApplied
            THEN 'chain-applied' ELSE 'chain-incomplete' END;
"@) | Select-Object -Last 1
                if ($applied.Trim() -eq 'chain-applied') { $migrated = $true; break }
            }
            catch {
                # Recorded rather than swallowed. A silent catch here is what turned a type error
                # into a misleading timeout message.
                $lastReadinessError = $_.Exception.Message
            }
        }
        Start-Sleep -Seconds 3
    }
    if (-not $portOpen) { throw "the application never answered /api/v1/status on port $ApiPort" }
    if (-not $migrated) {
        throw ("the web port opened but the Flyway chain never reached $expectedApplied applied " +
            "migrations. Refusing to load a dataset into a database whose schema is not ready." +
            $(if ($lastReadinessError) { " Last readiness read failed with: $lastReadinessError" } else { '' }))
    }
    Write-QLog "        ready: /api/v1/status is 200 AND all $expectedApplied migrations are applied"

    # --- 3. the dataset ---
    Write-QLog "STEP 3/5  loading tests/performance/dataset.sql (this writes a quarter of a million rows)"
    $seedText = [System.IO.File]::ReadAllText($datasetSql)
    if ($seedText -notmatch '\$fe_perf_bcrypt\$') {
        throw ("dataset.sql does not contain the `$fe_perf_bcrypt` placeholder, so its password " +
            "hash cannot be substituted. Refusing to start it, because the seeded hash would then " +
            "be whatever the file happens to contain.")
    }
    $resolved = $seedText.Replace('$fe_perf_bcrypt$', $perfHash)
    $seedOut = ($resolved | docker exec -i $Container psql -U $DbUser -d $Database `
                    -v ON_ERROR_STOP=1 -q -f - 2>&1) | ForEach-Object { $_.ToString() }
    if ($LASTEXITCODE -ne 0) { throw "dataset.sql failed:`n$($seedOut -join "`n")" }
    $seedOut | Where-Object { $_ -match '\S' } | Select-Object -Last 12 | ForEach-Object { Write-Host "        $_" }

    # --- 4. authenticate ---
    Write-QLog "STEP 4/5  signing in as the seeded performance identity"
    $api = "http://localhost:$ApiPort"

    # CSRF first, and SELF-DESCRIBING: the token endpoint publishes both the header name and
    # the token, so this does not hard-code `X-XSRF-TOKEN` and cannot rot if the name changes.
    #
    # The first attempt at this omitted the header and got 403 CSRF_TOKEN_INVALID, which is the
    # right answer from the server: login is a CSRF-protected mutation. See the note on login's
    # declared responses -- the document does not currently declare that 403.
    $csrf = Invoke-RestMethod -Uri "$api/api/v1/auth/csrf" -SessionVariable feSession -TimeoutSec 30
    if (-not $csrf.headerName -or -not $csrf.token) {
        throw ("the CSRF endpoint returned no headerName/token, so login cannot be sent safely: " +
            ($csrf | ConvertTo-Json -Compress))
    }
    $loginBody = @{ email = $perfEmail; password = $perfPassword } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$api/api/v1/auth/login" -Method Post -Body $loginBody `
        -ContentType 'application/json' -WebSession $feSession -TimeoutSec 30 `
        -Headers @{ $csrf.headerName = $csrf.token }
    $cookie = ($feSession.Cookies.GetCookies($api) | Where-Object { $_.Name -eq 'fe_session' } | Select-Object -First 1)
    if (-not $cookie) { throw "login returned $($login.userId) but no fe_session cookie was set" }
    Write-QLog ("        signed in as {0} (role {1}); the credential is discarded at teardown" -f $login.email, $login.role)

    # --- 5. the smoke ---
    Write-QLog "STEP 5/5  running tests/performance/run-smoke.ps1"
    $smokeExit = 0
    & powershell -NoProfile -ExecutionPolicy Bypass -File $smokeScript `
        -TargetBaseUrl $api `
        -ManagementBaseUrl "http://localhost:$ManagementPort" `
        -SessionCookie $cookie.Value `
        -RepoRoot $RepoRoot `
        -Thresholds $thresholds `
        -Report $Report `
        -Container $Container `
        -DbUser $DbUser `
        -TelemetryDatabase $Database | Write-Host
    $smokeExit = $LASTEXITCODE

    if ($smokeExit -eq 0) {
        Write-QLog "performance smoke PASS -- report $Report" -Level STEP
        $exit = 0
    }
    else {
        Write-QLog "performance smoke FAIL (exit $smokeExit) -- report $Report" -Level ERROR
        $exit = 1
    }
}
catch {
    Write-QLog "performance smoke could not be completed: $($_.Exception.Message)" -Level ERROR
    $exit = 1
}
finally {
    # --- teardown, on every path including failure ---
    if ($appProcess -and -not $appProcess.HasExited) {
        Write-QLog "tearing down: stopping the application"
        Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
        # Wait for it. The first attempt at this dropped the database while the JVM was still
        # shutting down and PostgreSQL refused with "being accessed by other users" -- which on
        # the failure path would have left the throwaway credential's hash on disk, which is the
        # one thing this design exists to prevent.
        $null = $appProcess.WaitForExit(30000)
    }
    # PHASE 47. -KeepDatabase leaves the throwaway schema standing so a latency finding can be
    # EXPLAINed instead of guessed at. It is OFF by default precisely because the whole point of
    # this wrapper is that the credential it creates stops existing; a switch that leaks one by
    # accident would undo the property the stage was built for. The name is printed when it is on,
    # so nobody ends up wondering where an extra database came from.
    if ($KeepDatabase) {
        Write-QLog "KEEPING $Database (-KeepDatabase): drop it yourself when you are done"
    }
    else {
    Write-QLog "tearing down: dropping $Database, so the throwaway credential stops existing"
    try {
        # Evict any connection that outlived the JVM, then retry: a container's own pool, or a
        # psql session still closing, would otherwise fail the DROP exactly when it matters most.
        $evict = @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
 WHERE datname = '$Database' AND pid <> pg_backend_pid();
"@
        $dropped = $false
        for ($attempt = 1; $attempt -le 3 -and -not $dropped; $attempt++) {
            $null = ($evict | docker exec -i $Container psql -U $DbUser -d postgres -q -f - 2>&1)
            $dropOut = docker exec $Container psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $Database" 2>&1
            if ($LASTEXITCODE -eq 0) { $dropped = $true; Write-Host "        dropped" }
            else {
                if ($attempt -eq 3) {
                    Write-QLog ("could not drop $Database after 3 attempts: " + ($dropOut -join ' ')) -Level ERROR
                }
                Start-Sleep -Seconds 5
            }
        }
    }
    catch { Write-QLog "could not drop ${Database} : $($_.Exception.Message)" -Level ERROR }
    # The plaintext credential goes out of scope here and is never written anywhere.
    $perfPassword = $null
    }
}

exit $exit
