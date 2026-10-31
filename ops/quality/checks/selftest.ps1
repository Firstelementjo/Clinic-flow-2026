#!/usr/bin/env pwsh
# =====================================================================
# selftest.ps1
#
# Proves that the gate scripts FAIL when they are supposed to.
#
# A gate that has only ever been observed to pass is not a gate. Every
# check under ops/quality/checks has a fixture here that provokes the
# failure it exists to detect, and this script asserts the exact exit
# code. It uses synthetic JUnit XML and synthetic source trees only: no
# database, no container, no network, no Gradle.
#
# This is the part of the quality pipeline that can be executed by any
# developer on any machine in about ten seconds, and it is the reason a
# red stage in CI is trustworthy.
#
# Exit codes: 0 = every expectation held, 1 = a gate did not behave.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $JsonOut,
    # Fixed clock so waiver expiry is deterministic.
    [string] $Now = '2026-10-04T12:00:00Z'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$fixtures   = Join-Path $RepoRoot 'tests\contracts\fixtures'
$waivers    = Join-Path $fixtures 'waivers'
$emptyAllow = Join-Path $RepoRoot 'ops\quality\waivers\no-hidden-skips.allow.json'
$allowRoot  = Join-Path $RepoRoot 'ops\quality\waivers'
$scratch    = New-QDirectory (Join-Path $RepoRoot 'build\quality-selftest')

$skipChecker = Join-Path $RepoRoot 'ops\quality\checks\check-no-hidden-skips.ps1'
$ps = Resolve-QPowerShell

$cases = New-Object System.Collections.Generic.List[object]
$failures = 0

function Add-Case {
    param(
        [string]   $Name,
        [string]   $Expectation,
        [int]      $ExpectedExit,
        [string[]] $Arguments,
        # A non-zero exit is not proof of the right failure: a parser error in
        # the checker also exits 1. Every failing case therefore has to name a
        # marker string that must appear in the checker's own output.
        [string[]] $ExpectContains = @(),
        [string[]] $ExpectAbsent  = @()
    )
    $logName = (ConvertTo-QSlug -Text $Name)
    $log = Join-Path $scratch "$logName.log"
    $r = Invoke-QProcess -Exe $ps.Exe -Arguments (@('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $skipChecker) + $Arguments) -LogPath $log

    $logText = if (Test-Path -LiteralPath $log) { Get-Content -Raw -LiteralPath $log } else { '' }
    $markersOk = $true
    $missingMarkers = New-Object System.Collections.Generic.List[string]
    foreach ($m in $ExpectContains) {
        if ($logText -notlike "*$m*") { $markersOk = $false; $missingMarkers.Add($m) }
    }
    foreach ($m in $ExpectAbsent) {
        if ($logText -like "*$m*") { $markersOk = $false; $missingMarkers.Add("absent: $m") }
    }

    $expectedWord = if ($ExpectedExit -eq 0) { 'PASS' } else { 'FAIL' }
    $ok = ($r.exitCode -eq $ExpectedExit) -and $markersOk
    if (-not $ok) { $script:failures++ }

    $cases.Add([ordered]@{
        name          = $Name
        expectation   = $Expectation
        expectedExit  = $ExpectedExit
        actualExit    = $r.exitCode
        verdict       = $expectedWord
        expectContains = ConvertTo-QArray -Value $ExpectContains
        expectAbsent  = ConvertTo-QArray -Value $ExpectAbsent
        unmatchedMarkers = ConvertTo-QArray -Value $missingMarkers
        matched       = $ok
        log           = $log
    })

    $mark = if ($ok) { '  ok  ' } else { ' FAIL ' }
    Write-QLog ("{0}{1,-52} expected exit {2}, got {3}{4}" -f $mark, $Name, $ExpectedExit, $r.exitCode, `
        $(if ($markersOk) { '' } else { " (missing marker(s): $($missingMarkers -join ' | '))" })) `
        -Level $(if ($ok) { 'INFO' } else { 'ERROR' })
    return $ok
}

Write-QLog 'no-hidden-skips self-test' -Level STEP
Write-QLog ("clock: {0}" -f $Now)

# --- 1. a clean result set passes ---------------------------------
Add-Case -Name 'clean results, both required suites present' `
         -Expectation 'exit 0' -ExpectedExit 0 `
         -ExpectContains @('VERDICT: PASS') -ExpectAbsent @('SKIPPED TEST', 'REQUIRED SUITE', 'VIOLATION', 'STALE WAIVER') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\clean'),
            '-ModuleScope', 'tenant-security',
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', $emptyAllow,
            '-Now', $Now,
            '-JsonOut', (Join-Path $scratch 'case1.json')
        ) | Out-Null

# --- 2. an honestly reported skip fails ---------------------------
Add-Case -Name 'one honestly reported skip' `
         -Expectation 'exit 1 -- a skip is a failure' -ExpectedExit 1 `
         -ExpectContains @('SKIPPED TEST (not waived)') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\skipped'),
            '-ModuleScope', 'tenant-security',
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', $emptyAllow,
            '-Now', $Now,
            '-JsonOut', (Join-Path $scratch 'case2.json')
        ) | Out-Null

# --- 3. a mis-reported skip fails too -----------------------------
Add-Case -Name 'skipped element behind a skipped="0" attribute' `
         -Expectation 'exit 1 -- the attribute is not trusted' -ExpectedExit 1 `
         -ExpectContains @('contains 1 <skipped/> element(s)', 'SKIPPED TEST (not waived)') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\misreported'),
            '-ModuleScope', 'tenant-security',
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', $emptyAllow,
            '-Now', $Now,
            '-JsonOut', (Join-Path $scratch 'case3.json')
        ) | Out-Null

# --- 4. a skip fails even with an empty required-suite manifest ---
Add-Case -Name 'skip with no required suites at all' `
         -Expectation 'exit 1 -- skip detection does not depend on the manifest' -ExpectedExit 1 `
         -ExpectContains @('SKIPPED TEST (not waived)') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\skipped'),
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture-empty.json'),
            '-AllowList', $emptyAllow,
            '-Now', $Now
        ) | Out-Null

# --- 5. a required suite that did not run fails -------------------
Add-Case -Name 'required suite absent from the results' `
         -Expectation 'exit 1 -- a vanished suite is not a passing suite' -ExpectedExit 1 `
         -ExpectContains @('REQUIRED SUITE MISSING') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\missing-suite'),
            '-ModuleScope', 'tenant-security',
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', $emptyAllow,
            '-Now', $Now,
            '-JsonOut', (Join-Path $scratch 'case5.json')
        ) | Out-Null

# --- 6. no results at all fails ------------------------------------
Add-Case -Name 'no JUnit XML at all (nothing ran)' `
         -Expectation 'exit 1 -- an empty result set is not evidence' -ExpectedExit 1 `
         -ExpectContains @('No TEST-*.xml files were found') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\empty'),
            '-ModuleScope', 'tenant-security',
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', $emptyAllow,
            '-Now', $Now
        ) | Out-Null

# --- 7. a narrow, in-date waiver is the one way to be green ------
Add-Case -Name 'skip covered by a narrow in-date waiver' `
         -Expectation 'exit 0 -- the waiver mechanism can grant, and only narrowly' -ExpectedExit 0 `
         -ExpectContains @('SKIP WAIVED', 'VERDICT: PASS') -ExpectAbsent @('STALE WAIVER') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\skipped'),
            '-ModuleScope', 'tenant-security',
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', (Join-Path $waivers 'valid.json'),
            '-Now', $Now,
            '-JsonOut', (Join-Path $scratch 'case7.json')
        ) | Out-Null

# --- 8. an expired waiver grants nothing --------------------------
Add-Case -Name 'skip covered only by an EXPIRED waiver' `
         -Expectation 'exit 1 -- expiry is enforced, not advisory' -ExpectedExit 1 `
         -ExpectContains @('EXPIRED on 2020-01-01', 'SKIPPED TEST (not waived)') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\skipped'),
            '-ModuleScope', 'tenant-security',
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', (Join-Path $waivers 'expired.json'),
            '-Now', $Now
        ) | Out-Null

# --- 9. a blanket waiver is rejected outright ---------------------
Add-Case -Name 'blanket wildcard waiver' `
         -Expectation 'exit 1 -- no blanket waivers, by construction' -ExpectedExit 1 `
         -ExpectContains @('blanket waiver and is never accepted') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\clean'),
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', (Join-Path $waivers 'blanket.json'),
            '-Now', $Now
        ) | Out-Null

# --- 10. a waiver without a ticket is rejected ---------------------
Add-Case -Name 'waiver with no ticket and no expiry' `
         -Expectation 'exit 1 -- an untracked waiver is not a waiver' -ExpectedExit 1 `
         -ExpectContains @('"ticket" is required') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\clean'),
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', (Join-Path $waivers 'incomplete.json'),
            '-Now', $Now
        ) | Out-Null

# --- 11. an unused waiver is rejected ------------------------------
Add-Case -Name 'well-formed but stale waiver' `
         -Expectation 'exit 1 -- stale waivers must be deleted' -ExpectedExit 1 `
         -ExpectContains @('STALE WAIVER') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\clean'),
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', (Join-Path $waivers 'stale.json'),
            '-Now', $Now
        ) | Out-Null

# --- 12. a display name is not an identifier ----------------------
Add-Case -Name 'waiver naming a @DisplayName instead of the class' `
         -Expectation 'exit 1 -- a stable identifier is required' -ExpectedExit 1 `
         -ExpectContains @('fully-qualified class name') `
         -Arguments @(
            '-ResultsDir', (Join-Path $fixtures 'junit\clean'),
            '-Manifest', (Join-Path $fixtures 'expected-suites.fixture.json'),
            '-AllowList', (Join-Path $waivers 'displayname.json'),
            '-Now', $Now
        ) | Out-Null

# ---------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------

$verdict = if ($failures -eq 0) { 'PASS' } else { 'FAIL' }

$result = [ordered]@{
    stage        = 'checks-selftest'
    verdict      = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    clock        = $Now
    caseCount    = $cases.Count
    failures     = $failures
    cases        = ConvertTo-QArray -Value $cases
}

if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

Write-QLog ''
Write-QLog ("selftest: {0}/{1} expectations held -- VERDICT {2}" -f ($cases.Count - $failures), $cases.Count, $verdict) -Level STEP
if ($failures -gt 0) { exit 1 }
exit 0