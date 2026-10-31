<#
.SYNOPSIS
    Runs the Flyway verification bundle against whichever schema profile the target
    database actually is, reading the expectations from ops/flyway/expected-state.json.

.DESCRIPTION
    Why this exists: the quality gate used to hardcode `-ExpectedEnumCount 23` for the
    development database and `27` for a disposable one. Both numbers were correct the
    day they were written and are wrong the moment the first Flyway-enabled boot stamps
    the baseline — at which point the pipeline fails on cutover day for a reason that has
    nothing to do with anybody's code. Worse, the tempting fix is to delete the
    assertions.

    So the expectations live in one reviewed file, this script decides which profile
    applies by asking the database whether flyway_schema_history exists, and it prints
    which profile it used. If the database matches neither profile the script FAILS. It
    never degrades to "run the bundle without assertions".

.EXAMPLE
    .\verify-schema-state.ps1 -Container firstelement-postgres -Database firstelement `
        -OutputDir .\evidence\devdb -ThrowOnViolation
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Container,
    [Parameter(Mandatory = $true)][string] $Database,
    [Parameter(Mandatory = $true)][string] $OutputDir,

    # auto        pick the profile from the database (default)
    # preBoot     require an unstamped database
    # postBoot    require a stamped database
    [ValidateSet('auto', 'preBoot', 'postBoot')][string] $RequireProfile = 'auto',

    [string] $DbUser = 'firstelement',
    [string] $ExpectedStatePath,
    [string] $JsonOut,
    [switch] $ThrowOnViolation
)

$ErrorActionPreference = 'Stop'

function Write-QLog {
    param([string] $Message, [ValidateSet('INFO', 'WARN', 'ERROR')][string] $Level = 'INFO')
    $ts = (Get-Date).ToUniversalTime().ToString('HH:mm:ss')
    $colour = switch ($Level) { 'WARN' { 'Yellow' } 'ERROR' { 'Red' } default { 'Cyan' } }
    Write-Host "$ts  $Level  [verify-schema-state] $Message" -ForegroundColor $colour
}

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)))
if (-not $ExpectedStatePath) {
    $ExpectedStatePath = Join-Path $repoRoot 'ops/flyway/expected-state.json'
}
if (-not (Test-Path -LiteralPath $ExpectedStatePath)) {
    throw "expected-state file not found: $ExpectedStatePath"
}

$expected = Get-Content -Raw -LiteralPath $ExpectedStatePath | ConvertFrom-Json

# --- Is the database stamped? This single fact selects the profile. ---------------
$sql = "select case when exists (select 1 from information_schema.tables" +
    " where table_schema='public' and table_name='flyway_schema_history') then 'true' else 'false' end;"
$raw = $sql | docker exec -i $Container psql -U $DbUser -d $Database -t -A -v ON_ERROR_STOP=1 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "could not read schema state from $Container/$Database : $raw"
}
$stamped = ($raw | Select-Object -Last 1).Trim() -eq 'true'
Write-QLog "$Database flyway_stamped=$stamped"

# --- Select the profile -----------------------------------------------------------
$profileName = if ($stamped) { 'postBoot' } else { 'preBoot' }
if ($RequireProfile -ne 'auto' -and $RequireProfile -ne $profileName) {
    Write-QLog ("profile mismatch: the caller demanded '{0}' but {1} is '{2}'" -f
        $RequireProfile, $Database, $profileName) 'ERROR'
    if ($ThrowOnViolation) { exit 1 }
    exit 1
}
$profile = $expected.profiles.$profileName
if ($null -eq $profile) {
    Write-QLog "expected-state.json has no '$profileName' profile" 'ERROR'
    exit 1
}

Write-QLog ("using profile '{0}': enums={1} tables={2} checks={3} expectedFingerprint={4}" -f
    $profileName, $profile.enumCount, $profile.tableCount, $profile.checkCount, $profile.fingerprint)

# --- Run the real bundle with this profile's assertions ---------------------------
$bundle = Join-Path $repoRoot 'ops/flyway/verification/run-verification.ps1'
$bundleArgs = @{
    Container          = $Container
    Database           = $Database
    OutputDir          = $OutputDir
    ExpectedEnumCount  = [int]$profile.enumCount
    ExpectedTableCount = [int]$profile.tableCount
    ExpectedCheckCount = [int]$profile.checkCount
}
if ($ThrowOnViolation) { $bundleArgs.ThrowOnViolation = $true }

& $bundle @bundleArgs
$bundleExit = $LASTEXITCODE

if ($JsonOut) {
    $summary = [ordered]@{
        database           = $Database
        flywayStamped      = $stamped
        profile            = $profileName
        expectedEnums      = [int]$profile.enumCount
        expectedTables     = [int]$profile.tableCount
        expectedChecks     = [int]$profile.checkCount
        expectedFingerprint= [string]$profile.fingerprint
        bundleExitCode     = $bundleExit
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $JsonOut) | Out-Null
    $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $JsonOut -Encoding UTF8
}

if ($bundleExit -ne 0) {
    Write-QLog "verification bundle FAILED under profile '$profileName' (exit $bundleExit)" 'ERROR'
    exit $bundleExit
}
Write-QLog "verification bundle PASSED under profile '$profileName'" 'INFO'
exit 0