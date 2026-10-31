#!/usr/bin/env pwsh
# =====================================================================
# Rehearse-ForensicScan.ps1
#
# The rehearsal. Every claim ops/forensics makes is executed here, and
# each one asserts an outcome rather than printing a message and
# continuing. A rehearsal that cannot go red proves nothing.
#
# Seven scenarios:
#
#   R1  the scanner REFUSES the protected database `firstelement`
#   R2  the scanner REFUSES a target that is not marked as forensics
#   R3  the scanner's session is READ ONLY, proved by PostgreSQL, with
#       a paired control proving the statement is otherwise valid
#   R4  FE-FOR-REDACT-001 refuses a query that selects patient data
#   R5  SEEDED CLEAN single-tenant data -> CLEAN, exit 0, zero findings
#   R6  SEEDED CONTAMINATED data       -> FINDINGS, exit 1, and EVERY
#                                        one of the eight checks fires
#   R7  NO DATA at all                 -> INCONCLUSIVE, exit 3, and NOT
#                                        exit 0
#   R8  no patient data appears in the report, verified by searching the
#       rendered output for the fixture's patient/doctor/procedure names
#
# R5, R6 and R7 together are the reason a clean answer on production is
# interpretable: a query that cannot tell those three apart is not a
# forensic tool, it is a mood ring.
#
# Lifecycle: the disposable database is created and dropped through
# ops/quality/checks/prepare-throwaway-db.ps1, the same guard shape every
# other rehearsal in this repository uses. This script never targets
# anything it did not create.
#
# Exit codes: 0 all scenarios passed, 1 a scenario failed, 2 setup
# could not run at all.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $ProtectedDatabase = 'firstelement',
    [string] $ArtifactDir,
    [switch] $KeepDatabase
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\quality\lib\quality-common.ps1')

if (-not $ArtifactDir) { $ArtifactDir = Join-Path $PSScriptRoot '.rehearsal' }
$ArtifactDir = New-QDirectory -Path $ArtifactDir

$repoRoot   = Get-RepositoryRoot -StartFrom $PSScriptRoot
$scanner    = Join-Path $PSScriptRoot 'Invoke-ForensicScan.ps1'
$prepareDb  = Join-Path $repoRoot 'ops\quality\checks\prepare-throwaway-db.ps1'
$cleanSeed  = Join-Path $PSScriptRoot 'fixtures\seed-clean-single-tenant.sql'
$dirtySeed  = Join-Path $PSScriptRoot 'fixtures\seed-contaminated-cross-tenant.sql'

# Every database this script creates carries `forensic`, which is what
# Invoke-ForensicScan.ps1 requires. Both are `check`-suffixed so that
# prepare-throwaway-db.ps1's own disposable-name rule is satisfied too.
$dbClean = 'forensics_rehearsal_check'
$dbEmpty = 'forensics_empty_check'

$scenarios = New-Object System.Collections.Generic.List[object]
function Scenario {
    param([string] $Id, [string] $Title, [bool] $Passed, [string] $Detail)
    $script:scenarios.Add([ordered]@{ id = $Id; title = $Title; passed = $Passed; detail = $Detail })
    $mark = if ($Passed) { 'ok  ' } else { 'FAIL' }
    Write-QLog ("  {0} {1} {2}" -f $mark, $Id, $Title) -Level $(if ($Passed) { 'INFO' } else { 'ERROR' })
    if (-not $Passed) { Write-QLog ('       {0}' -f $Detail) -Level ERROR }
}

function Invoke-Scanner {
    param([string] $Database, [string] $SqlPath, [string] $JsonName)
    $jsonPath = Join-Path $ArtifactDir "$JsonName.json"
    $logPath  = Join-Path $ArtifactDir "$JsonName.log"
    $r = Invoke-QProcess -Exe 'powershell.exe' `
            -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scanner,
                         '-Database', $Database, '-Container', $Container, '-DbUser', $DbUser,
                         '-ProtectedDatabase', $ProtectedDatabase, '-JsonOut', $jsonPath) `
            -LogPath $logPath -TimeoutMinutes 10
    return [ordered]@{ exitCode = $r.exitCode; logPath = $logPath; jsonPath = $jsonPath }
}

function Invoke-Sql {
    param([string] $Sql, [string] $Db)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = ($Sql | & docker exec -i $Container psql -U $DbUser -d $Db -v ON_ERROR_STOP=1 -t -A -P pager=off -f - 2>&1) |
        ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $previous
    return [ordered]@{ output = @($out); exitCode = $LASTEXITCODE }
}

# --- setup -----------------------------------------------------------
Write-QLog '==> forensic rehearsal: building disposable targets' -Level STEP
foreach ($db in @($dbClean, $dbEmpty)) {
    $drop = Invoke-QProcess -Exe 'powershell.exe' `
        -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $prepareDb,
                     '-Database', $db, '-Container', $Container, '-DbUser', $DbUser,
                     '-ProtectedDatabase', $ProtectedDatabase, '-Dispose') -TimeoutMinutes 10
    if ($drop.exitCode -ne 0) {
        Write-QLog ("could not clear {0}: exit {1}" -f $db, $drop.exitCode) -Level ERROR
        exit 2
    }
}

# =====================================================================
# R1 -- refuses the protected database
# =====================================================================
Write-QLog ''
Write-QLog '== R1: the scanner must refuse the protected database ==' -Level STEP
$r1 = Invoke-QProcess -Exe 'powershell.exe' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scanner,
                 '-Database', $ProtectedDatabase, '-ProtectedDatabase', $ProtectedDatabase) `
    -LogPath (Join-Path $ArtifactDir 'R1-refuse-protected.log') -TimeoutMinutes 5
Scenario -Id 'R1' -Title ("refuses -Database {0}" -f $ProtectedDatabase) `
    -Passed ($r1.exitCode -eq 2) `
    -Detail ("expected exit 2, got {0}" -f $r1.exitCode)

# =====================================================================
# R2 -- refuses a target that is not marked as forensics
# =====================================================================
Write-QLog ''
Write-QLog '== R2: the scanner must refuse a non-forensics target name ==' -Level STEP
$r2 = Invoke-QProcess -Exe 'powershell.exe' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scanner,
                 '-Database', 'production_copy', '-ProtectedDatabase', $ProtectedDatabase) `
    -LogPath (Join-Path $ArtifactDir 'R2-refuse-non-forensics.log') -TimeoutMinutes 5
Scenario -Id 'R2' -Title 'refuses a database name that does not contain "forensic"' `
    -Passed ($r2.exitCode -eq 2) `
    -Detail ("expected exit 2, got {0}" -f $r2.exitCode)

# =====================================================================
# R3 -- the session is read only, proved by the database
# =====================================================================
# The write probe is run TWICE: once through the runner's session
# settings, which must be rejected, and once without them, which must
# succeed. The control is the point -- a rejected statement proves
# nothing unless the same statement is shown to be valid.
Write-QLog ''
Write-QLog '== R3: the scanner session cannot write (paired with a control) ==' -Level STEP
$probe = @'
INSERT INTO organizations (id,name,slug,"isActive","createdAt","updatedAt")
VALUES ('ffffffff-0000-4000-8000-0000000000f1','ForensicsWriteProbe','forensics-write-probe',true,'2026-01-01','2026-01-01');
'@

# Build the clean target first so R3 has a populated database to probe.
$build = Invoke-QProcess -Exe 'powershell.exe' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $prepareDb,
                 '-Database', $dbClean, '-Container', $Container, '-DbUser', $DbUser,
                 '-ProtectedDatabase', $ProtectedDatabase, '-MigrateChain') `
    -LogPath (Join-Path $ArtifactDir 'R3-build.log') -TimeoutMinutes 30
if ($build.exitCode -ne 0) {
    Write-QLog ("could not build {0}: exit {1}. Full log: {2}" -f $dbClean, $build.exitCode, $build.log) -Level ERROR
    exit 2
}

$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$guarded = ($probe | & docker exec -i -e 'PGOPTIONS=-c default_transaction_read_only=on' $Container psql -U $DbUser -d $dbClean -v ON_ERROR_STOP=1 -t -A -P pager=off -f - 2>&1) | ForEach-Object { $_.ToString() }
$guardedExit = $LASTEXITCODE
$control = ($probe | & docker exec -i $Container psql -U $DbUser -d $dbClean -v ON_ERROR_STOP=1 -t -A -P pager=off -f - 2>&1) | ForEach-Object { $_.ToString() }
$controlExit = $LASTEXITCODE
$ErrorActionPreference = $previous
Scenario -Id 'R3a' -Title 'write rejected under the scanner session settings' `
    -Passed ($guardedExit -ne 0) `
    -Detail ("the INSERT was expected to fail, but exited {0}" -f $guardedExit)
Scenario -Id 'R3b' -Title 'the same write succeeds without them (the control)' `
    -Passed ($controlExit -eq 0) `
    -Detail ("expected the control INSERT to succeed, exit was {0}; without this, R3a proves nothing" -f $controlExit)
$null = Invoke-Sql -Db $dbClean -Sql @'
DELETE FROM organizations WHERE slug='forensics-write-probe';
'@

# R3a/R3b prove PostgreSQL honours the setting. R3c proves THIS SCANNER
# actually runs under it -- the two are different claims, and during
# development a mutation that deleted the scanner's own guard passed the
# whole rehearsal until this assertion existed. A scanner that lost its
# guard still produces a plausible report, so the guard has to be
# self-verified.
$r3c = Invoke-Scanner -Database $dbClean -JsonName 'R3c-session-readonly'
$j3c = $null
if (Test-Path -LiteralPath $r3c.jsonPath) { $j3c = Get-Content -Raw -LiteralPath $r3c.jsonPath | ConvertFrom-Json }
Scenario -Id 'R3c' -Title 'the scanner self-reports a read-only session' `
    -Passed ($j3c -ne $null -and $j3c.sessionReadOnly -eq 'on') `
    -Detail ('expected sessionReadOnly "on", got ' + $(if ($j3c) { "'{0}'" -f $j3c.sessionReadOnly } else { 'no json' }))

# =====================================================================
# R4 -- FE-FOR-REDACT-001
# =====================================================================
Write-QLog ''
Write-QLog '== R4: a query that selects patient data must be refused ==' -Level STEP
$leaky = Join-Path $ArtifactDir 'leaky-select.sql'
Set-Content -LiteralPath $leaky -Value 'SELECT a.id, a."patientName" FROM appointments a;' -Encoding utf8
$metadataLeaky = Join-Path $ArtifactDir 'leaky-metadata.sql'
Set-Content -LiteralPath $metadataLeaky -Value 'SELECT a.id, l.metadata FROM audit_logs l;' -Encoding utf8
foreach ($pair in @(@('R4a', 'patientName', $leaky), @('R4b', 'audit metadata (carries patientPhone)', $metadataLeaky))) {
    $r = Invoke-QProcess -Exe 'powershell.exe' `
        -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scanner,
                     '-Database', $dbClean, '-SqlPath', $pair[2]) `
        -LogPath (Join-Path $ArtifactDir ("{0}-redact.log" -f $pair[0])) -TimeoutMinutes 5
    Scenario -Id $pair[0] -Title ("refuses a query selecting {0}" -f $pair[1]) `
        -Passed ($r.exitCode -eq 2) `
        -Detail ("expected exit 2, got {0}" -f $r.exitCode)
}

# =====================================================================
# R5 -- seeded CLEAN data must read clean, with coverage present
# =====================================================================
Write-QLog ''
Write-QLog '== R5: seeded single-tenant CLEAN data must produce CLEAN ==' -Level STEP
$null = Invoke-Sql -Db $dbClean -Sql (Get-Content -Raw -LiteralPath $cleanSeed)
$r5 = Invoke-Scanner -Database $dbClean -JsonName 'R5-clean'
$j5 = $null
if (Test-Path -LiteralPath $r5.jsonPath) { $j5 = Get-Content -Raw -LiteralPath $r5.jsonPath | ConvertFrom-Json }
Scenario -Id 'R5a' -Title 'clean fixture exits 0' `
    -Passed ($r5.exitCode -eq 0) `
    -Detail ("expected exit 0, got {0}. Full log: {1}" -f $r5.exitCode, $r5.logPath)
Scenario -Id 'R5b' -Title 'clean fixture reports zero findings' `
    -Passed ($j5 -ne $null -and @($j5.findings).Count -eq 0) `
    -Detail ("expected 0 findings, got {0}" -f $(if ($j5) { @($j5.findings).Count } else { 'no json' }))
Scenario -Id 'R5c' -Title 'clean fixture had coverage to look with' `
    -Passed ($j5 -ne $null -and $j5.coverage.canConclude -eq $true) `
    -Detail ('a CLEAN verdict must only be reachable when coverage exists; coverage said ' + $(if ($j5) { $j5.coverage.canConclude } else { 'no json' }))
Scenario -Id 'R5d' -Title 'clean fixture still reports the roster blind spot' `
    -Passed ($j5 -ne $null -and @($j5.limits | Where-Object { $_ -match 'ROSTER WRITES ARE INVISIBLE' }).Count -ge 1) `
    -Detail 'a clean result that omitted the roster blind spot would be the exact false assurance this tool exists to prevent'

# =====================================================================
# R6 -- seeded CONTAMINATED data must produce FINDINGS on every check
# =====================================================================
Write-QLog ''
Write-QLog '== R6: seeded CONTAMINATED data must produce FINDINGS on all eight checks ==' -Level STEP
$rebuild = Invoke-QProcess -Exe 'powershell.exe' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $prepareDb,
                 '-Database', $dbClean, '-Container', $Container, '-DbUser', $DbUser,
                 '-ProtectedDatabase', $ProtectedDatabase, '-MigrateChain', '-Dispose') -TimeoutMinutes 30
$rebuild2 = Invoke-QProcess -Exe 'powershell.exe' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $prepareDb,
                 '-Database', $dbClean, '-Container', $Container, '-DbUser', $DbUser,
                 '-ProtectedDatabase', $ProtectedDatabase, '-MigrateChain') `
    -LogPath (Join-Path $ArtifactDir 'R6-build.log') -TimeoutMinutes 30
if ($rebuild2.exitCode -ne 0) {
    Write-QLog ("could not rebuild {0}: exit {1}" -f $dbClean, $rebuild2.exitCode) -Level ERROR
    exit 2
}
$null = Invoke-Sql -Db $dbClean -Sql (Get-Content -Raw -LiteralPath $dirtySeed)
$r6 = Invoke-Scanner -Database $dbClean -JsonName 'R6-contaminated'
$j6 = $null
if (Test-Path -LiteralPath $r6.jsonPath) { $j6 = Get-Content -Raw -LiteralPath $r6.jsonPath | ConvertFrom-Json }
Scenario -Id 'R6a' -Title 'contaminated fixture exits 1' `
    -Passed ($r6.exitCode -eq 1) `
    -Detail ("expected exit 1, got {0}. Full log: {1}" -f $r6.exitCode, $r6.logPath)

$expectedChecks = @('FE-FOR-001', 'FE-FOR-002', 'FE-FOR-003', 'FE-FOR-004',
                    'FE-FOR-005', 'FE-FOR-006', 'FE-FOR-007', 'FE-FOR-008')
$firedChecks = @()
if ($j6) { $firedChecks = @($j6.findings | ForEach-Object { $_.checkId } | Sort-Object -Unique) }
foreach ($c in $expectedChecks) {
    Scenario -Id ("R6-{0}" -f $c.Substring(7)) -Title ("{0} fires on the contaminated fixture" -f $c) `
        -Passed ($firedChecks -contains $c) `
        -Detail ("{0} did not fire. A forensic check that has never been observed to fire is indistinguishable from one that cannot fire. Checks that fired: {1}" -f $c, ($firedChecks -join ', '))
}

# =====================================================================
# R7 -- no data must be INCONCLUSIVE, and must NOT be exit 0
# =====================================================================
Write-QLog ''
Write-QLog '== R7: a database with no audit coverage must be INCONCLUSIVE, not clean ==' -Level STEP
$emptyBuild = Invoke-QProcess -Exe 'powershell.exe' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $prepareDb,
                 '-Database', $dbEmpty, '-Container', $Container, '-DbUser', $DbUser,
                 '-ProtectedDatabase', $ProtectedDatabase, '-MigrateChain') `
    -LogPath (Join-Path $ArtifactDir 'R7-build.log') -TimeoutMinutes 30
if ($emptyBuild.exitCode -ne 0) {
    Write-QLog ("could not build {0}: exit {1}" -f $dbEmpty, $emptyBuild.exitCode) -Level ERROR
    exit 2
}
$r7 = Invoke-Scanner -Database $dbEmpty -JsonName 'R7-inconclusive'
$j7 = $null
if (Test-Path -LiteralPath $r7.jsonPath) { $j7 = Get-Content -Raw -LiteralPath $r7.jsonPath | ConvertFrom-Json }
Scenario -Id 'R7a' -Title 'zero-coverage database exits 3, never 0' `
    -Passed ($r7.exitCode -eq 3) `
    -Detail ("expected exit 3, got {0}. Exit 0 here would be a clean all-clear on a database that could not be examined at all." -f $r7.exitCode)
Scenario -Id 'R7b' -Title 'zero-coverage database reports INCONCLUSIVE' `
    -Passed ($j7 -ne $null -and $j7.verdict -eq 'INCONCLUSIVE') `
    -Detail ('expected INCONCLUSIVE, got ' + $(if ($j7) { $j7.verdict } else { 'no json' }))
Scenario -Id 'R7c' -Title 'zero-coverage database says NO AUDIT COVERAGE out loud' `
    -Passed ($j7 -ne $null -and @($j7.limits | Where-Object { $_ -match 'NO AUDIT COVERAGE' }).Count -ge 1) `
    -Detail 'the difference between "nothing happened" and "I cannot see" has to be on the face of the output'

# =====================================================================
# R8 -- no patient data reaches the report
# =====================================================================
# The fixtures deliberately populate patientName, doctor name and
# procedure name with recognisable tokens. If the query ever grew a
# column, or the runner ever printed raw psql output, these would appear.
Write-QLog ''
Write-QLog '== R8: the rendered report carries no patient data ==' -Level STEP
$mustNotAppear = @('SYNTHETIC-PATIENT', 'SYNTHETIC-DOCTOR', 'SYNTHETIC-PROCEDURE',
                   'Synthetic Actor', 'Synthetic Tenant', 'SYNTHETIC-NOT-A-HASH')
$leakyArtifacts = New-Object System.Collections.Generic.List[string]
foreach ($artifact in @(Get-ChildItem -LiteralPath $ArtifactDir -Filter 'R*-*.log' -File)) {
    $text = Get-Content -Raw -LiteralPath $artifact.FullName
    foreach ($needle in $mustNotAppear) {
        if ($text -match [regex]::Escape($needle)) {
            $leakyArtifacts.Add(("{0} contains '{1}'" -f $artifact.Name, $needle))
        }
    }
}
Scenario -Id 'R8' -Title 'no patient, doctor, procedure, actor or tenant name in any rendered report' `
    -Passed ($leakyArtifacts.Count -eq 0) `
    -Detail (($leakyArtifacts -join '; '))

# --- cleanup ---------------------------------------------------------
if (-not $KeepDatabase) {
    Write-QLog ''
    Write-QLog '==> disposing disposable targets' -Level STEP
    foreach ($db in @($dbClean, $dbEmpty)) {
        $null = Invoke-QProcess -Exe 'powershell.exe' `
            -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $prepareDb,
                         '-Database', $db, '-Container', $Container, '-DbUser', $DbUser,
                         '-ProtectedDatabase', $ProtectedDatabase, '-Dispose') -TimeoutMinutes 10
    }
}

# --- verdict ---------------------------------------------------------
$failed = @($scenarios | Where-Object { -not $_.passed })
$verdict = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }

Write-QLog ''
Write-QLog ("forensic rehearsal: {0}/{1} scenario(s) passed" -f ($scenarios.Count - $failed.Count), $scenarios.Count) -Level STEP
foreach ($f in $failed) { Write-QLog ('  FAILED {0} {1}: {2}' -f $f.id, $f.title, $f.detail) -Level ERROR }
Write-QLog ("forensic rehearsal VERDICT: {0}" -f $verdict) -Level $(if ($verdict -eq 'PASS') { 'STEP' } else { 'ERROR' })

$result = [ordered]@{
    check        = 'forensic-rehearsal'
    verdict      = $verdict
    ranAtUtc     = (Get-Date).ToUniversalTime().ToString('o')
    artifactsDir = $ArtifactDir
    scenarios    = ConvertTo-QArray -Value $scenarios
    scenarioCount = $scenarios.Count
    failedCount   = $failed.Count
}
Write-QJson -Path (Join-Path $ArtifactDir 'rehearsal.json') -Object $result -Depth 6 | Out-Null

if ($verdict -eq 'FAIL') { exit 1 }
exit 0