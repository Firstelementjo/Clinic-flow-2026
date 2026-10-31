#!/usr/bin/env pwsh
# =====================================================================
# selftest-advice-order.ps1
#
# Proves that check-advice-order.ps1 FAILS when it should.
#
# A gate that has only ever been observed to pass is not a gate. Every
# rule in check-advice-order.ps1 has a synthetic source tree here that
# provokes it, and this script asserts the exact exit code AND a marker
# string from the checker's own output -- because a non-zero exit is not
# proof of the right failure: a parse error also exits 1.
#
# The trees are written to build/quality-advice-selftest/ and contain
# synthetic Java sources only. No database, no container, no network, no
# Gradle, and no file under backend/ is read or written: each fixture
# gets its own throwaway RepoRoot, so the real tree cannot be the thing
# that made a case pass.
#
# Separated from selftest.ps1 rather than added to it because that file
# is owned by the quality agent and probes exactly one checker. Editing
# it was out of scope for the phase that added this gate.
#
# Exit codes: 0 = every expectation held, 1 = a gate did not behave.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $JsonOut
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$checker = Join-Path $PSScriptRoot 'check-advice-order.ps1'
$ps = Resolve-QPowerShell

# Deliberately NOT under the repository's build/ directory. The checker skips
# any path containing a `build` segment -- that is how it ignores Gradle output
# -- so a fixture written under build/ would be invisible to it and every case
# would report "0 advices" rather than the defect it provoked. The first
# version of this self-test did exactly that and every case failed for the same
# uninformative reason.
$scratchRoot = Join-Path 'C:\Users\adama\AppData\Local\Temp\opencode' ('fe-advice-selftest-' + (Get-QUtcTimestamp))
if (-not (Test-Path -LiteralPath (Split-Path -Parent $scratchRoot))) {
    $scratchRoot = Join-Path ([IO.Path]::GetTempPath()) ('fe-advice-selftest-' + (Get-QUtcTimestamp))
}
$scratch = New-QDirectory $scratchRoot

$cases = New-Object System.Collections.Generic.List[object]
$failures = 0

# ---------------------------------------------------------------------
# Fixture writers
# ---------------------------------------------------------------------

function New-FixtureRoot {
    <#
      .SYNOPSIS
      A throwaway RepoRoot with a backend/<module>/src/main/java tree.
      get-Repository-root looks for settings.gradle.kts, so one is
      written; the checker never reads it, but a fixture that resolved
      differently from the real repository would not be proving anything.
    #>
    param([string] $Name)

    $root = New-QDirectory (Join-Path $scratch $Name)
    Set-Content -LiteralPath (Join-Path $root 'settings.gradle.kts') -Value '// synthetic fixture' -Encoding utf8
    return $root
}

function Add-Source {
    param([string] $Root, [string] $Module, [string] $Relative, [string] $Content)

    $dir = New-QDirectory (Join-Path $Root ("backend\{0}\src\main\java" -f $Module))
    $file = Join-Path $dir $Relative
    Set-Content -LiteralPath $file -Value $Content -Encoding utf8
    return $file
}

# The three production advices, verbatim in shape from the real ones. Every
# case starts from this so each one provokes exactly one rule.
function Add-HealthyLadder {
    param([string] $Root)

    Add-Source -Root $Root -Module 'tenant-security' -Relative 'SecurityExceptionAdvice.java' -Content @'
package com.firstelement.security.error;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SecurityExceptionAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> accessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body("denied");
    }
}
'@ | Out-Null

    Add-Source -Root $Root -Module 'crm-core' -Relative 'CrmExceptionAdvice.java' -Content @'
package com.firstelement.crm.error;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RestControllerAdvice
public class CrmExceptionAdvice {

    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @ExceptionHandler(RuntimeException.class)
    public String refused(RuntimeException ex) {
        return "refused";
    }
}
'@ | Out-Null

    Add-Source -Root $Root -Module 'app-bootstrap' -Relative 'ErrorHandlingAdvice.java' -Content @'
package com.firstelement.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ErrorHandlingAdvice {

    @ExceptionHandler(Exception.class)
    public String unhandled(Exception ex) {
        return "unhandled";
    }
}
'@ | Out-Null
}

# ---------------------------------------------------------------------
# Case runner
# ---------------------------------------------------------------------

function Invoke-Case {
    param(
        [string] $Name,
        [string] $Expectation,
        [int] $ExpectedExit,
        [string] $FixtureRoot,
        [string[]] $ExpectContains = @(),
        [string[]] $ExpectAbsent = @(),
        [switch] $IncludeTestSources
    )

    $log = Join-Path $scratch ((ConvertTo-QSlug -Text $Name) + '.log')
    $args = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $checker,
        '-RepoRoot', $FixtureRoot, '-JsonOut', (Join-Path $scratch 'case.json'))
    if ($IncludeTestSources) { $args += '-IncludeTestSources' }

    $r = Invoke-QProcess -Exe $ps.Exe -Arguments $args -LogPath $log
    $logText = if (Test-Path -LiteralPath $log) { Get-Content -Raw -LiteralPath $log } else { '' }

    $markersOk = $true
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($m in $ExpectContains) {
        if ($logText -notlike "*$m*") { $markersOk = $false; $missing.Add($m) }
    }
    foreach ($m in $ExpectAbsent) {
        if ($logText -like "*$m*") { $markersOk = $false; $missing.Add("absent: $m") }
    }

    $ok = ($r.exitCode -eq $ExpectedExit) -and $markersOk
    if (-not $ok) { $script:failures++ }

    $cases.Add([ordered]@{
        name          = $Name
        expectation   = $Expectation
        expectedExit  = $ExpectedExit
        actualExit    = $r.exitCode
        verdict       = $(if ($ExpectedExit -eq 0) { 'PASS' } else { 'FAIL' })
        expectContains = ConvertTo-QArray -Value $ExpectContains
        expectAbsent  = ConvertTo-QArray -Value $ExpectAbsent
        unmatchedMarkers = ConvertTo-QArray -Value $missing
        matched       = $ok
        log           = $log
    })

    $mark = if ($ok) { '  ok  ' } else { ' FAIL ' }
    Write-QLog ("{0}{1,-54} expected exit {2}, got {3}{4}" -f $mark, $Name, $ExpectedExit, $r.exitCode, `
        $(if ($markersOk) { '' } else { " (missing: $($missing -join ' | '))" })) `
        -Level $(if ($ok) { 'INFO' } else { 'ERROR' })
}

Write-QLog 'advice-order self-test' -Level STEP

# --- 0. the healthy ladder passes ------------------------------------
# Without this, "the gate is red" could mean the gate is simply broken and
# red on everything. A red gate that cannot be green proves nothing about
# what it detects.
$clean = New-FixtureRoot 'clean'
Add-HealthyLadder -Root $clean
Invoke-Case -Name 'a healthy, fully ordered ladder passes' `
    -Expectation 'exit 0 -- the gate is capable of being green' -ExpectedExit 0 `
    -ExpectContains @('advice-order VERDICT: PASS', 'advices found: 3', 'catch-all floors: 1') `
    -ExpectAbsent @('FE-ADVICE-001:', 'FE-ADVICE-002:', 'VERDICT: FAIL') `
    -FixtureRoot $clean

# --- 1. an unordered advice fails (FE-ADVICE-001) --------------------
$unordered = New-FixtureRoot 'unordered'
Add-HealthyLadder -Root $unordered
Add-Source -Root $unordered -Module 'reporting-exports' -Relative 'ExportExceptionHandler.java' -Content @'
package com.firstelement.exports.web;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ExportController.class)
public class ExportExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public String illegalState(IllegalStateException ex) {
        return "conflict";
    }
}
'@ | Out-Null
Invoke-Case -Name 'a fourth advice with no @Order' `
    -Expectation 'exit 1 -- a tie with the floor is decided by component-scan order' -ExpectedExit 1 `
    -ExpectContains @('FE-ADVICE-001', 'ExportExceptionHandler declares no @Order', 'VERDICT: FAIL', 'UNORDERED') `
    -FixtureRoot $unordered

# --- 2. two advices with the same order fail (FE-ADVICE-002) --------
$duplicate = New-FixtureRoot 'duplicate-order'
Add-HealthyLadder -Root $duplicate
Add-Source -Root $duplicate -Module 'operations' -Relative 'TelemetryAdvice.java' -Content @'
package com.firstelement.operations;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RestControllerAdvice
public class TelemetryAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public String badArgument(IllegalArgumentException ex) {
        return "bad";
    }
}
'@ | Out-Null
Invoke-Case -Name 'two advices declaring the same order' `
    -Expectation 'exit 1 -- a tie between two advices is decided by component-scan order' -ExpectedExit 1 `
    -ExpectContains @('FE-ADVICE-002', 'declare order', 'VERDICT: FAIL') `
    -ExpectAbsent @('FE-ADVICE-001:') `
    -FixtureRoot $duplicate

# --- 3. a non-floor advice at LOWEST_PRECEDENCE (FE-ADVICE-003) -----
$lowest = New-FixtureRoot 'non-floor-lowest'
Add-HealthyLadder -Root $lowest
Add-Source -Root $lowest -Module 'workflows' -Relative 'SlaAdvice.java' -Content @'
package com.firstelement.automation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class SlaAdvice {

    @ExceptionHandler(IllegalStateException.class)
    public String conflict(IllegalStateException ex) {
        return "conflict";
    }
}
'@ | Out-Null
Invoke-Case -Name 'a non-floor advice sitting at LOWEST_PRECEDENCE' `
    -Expectation 'exit 1 -- it races the Exception catch-all' -ExpectedExit 1 `
    -ExpectContains @('FE-ADVICE-002', 'FE-ADVICE-003', 'race for every exception', 'VERDICT: FAIL') `
    -FixtureRoot $lowest

# --- 4. two floors fail (FE-ADVICE-004) -----------------------------
$twoFloors = New-FixtureRoot 'two-floors'
Add-HealthyLadder -Root $twoFloors
Add-Source -Root $twoFloors -Module 'channels' -Relative 'ChannelFallbackAdvice.java' -Content @'
package com.firstelement.channels;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE - 200)
@RestControllerAdvice
public class ChannelFallbackAdvice {

    @ExceptionHandler(Exception.class)
    public String fallback(Exception ex) {
        return "fallback";
    }
}
'@ | Out-Null
Invoke-Case -Name 'two advices holding @ExceptionHandler(Exception.class)' `
    -Expectation 'exit 1 -- two catch-alls compete for every exception' -ExpectedExit 1 `
    -ExpectContains @('FE-ADVICE-004', 'catch-all floors: 2', 'VERDICT: FAIL') `
    -FixtureRoot $twoFloors

# --- 5. a floor that is not last fails (FE-ADVICE-004) --------------
$floorFirst = New-FixtureRoot 'floor-not-last'
Add-Source -Root $floorFirst -Module 'app-bootstrap' -Relative 'ErrorHandlingAdvice.java' -Content @'
package com.firstelement.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ErrorHandlingAdvice {

    @ExceptionHandler(Exception.class)
    public String unhandled(Exception ex) {
        return "unhandled";
    }
}
'@ | Out-Null
Invoke-Case -Name 'the catch-all declared ahead of a specific advice' `
    -Expectation 'exit 1 -- every refusal would reach the floor first and become a 500' -ExpectedExit 1 `
    -ExpectContains @('FE-ADVICE-004', 'must be the last resort', 'VERDICT: FAIL') `
    -FixtureRoot $floorFirst

# --- 6. an unresolvable @Order fails (FE-ADVICE-006) ---------------
$unresolvable = New-FixtureRoot 'unresolvable'
Add-HealthyLadder -Root $unresolvable
Add-Source -Root $unresolvable -Module 'ai-rag' -Relative 'AiAdvice.java' -Content @'
package com.firstelement.airag;

import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(SomeExternalConfig.ADVICE_ORDER)
@RestControllerAdvice
public class AiAdvice {

    @ExceptionHandler(IllegalStateException.class)
    public String refused(IllegalStateException ex) {
        return "refused";
    }
}
'@ | Out-Null
Invoke-Case -Name 'an @Order this script cannot resolve to an int' `
    -Expectation 'exit 1 -- an unverifiable order is not an order' -ExpectedExit 1 `
    -ExpectContains @('FE-ADVICE-006', 'cannot resolve to an int', 'VERDICT: FAIL') `
    -ExpectAbsent @('FE-ADVICE-001:') `
    -FixtureRoot $unresolvable

# --- 7. javadoc quoting the annotations is not code ----------------
# The real SecurityExceptionAdvice javadoc names @RestControllerAdvice and
# basePackages in prose explaining why the scope was widened. A scanner that
# reads prose as code reports a phantom advice, then a phantom duplicate.
$prose = New-FixtureRoot 'javadoc-prose'
Add-HealthyLadder -Root $prose
# A file that MENTIONS the annotation and declares none. The healthy ladder's
# SecurityExceptionAdvice already sits at HIGHEST_PRECEDENCE, so a phantom read
# of this prose would be reported as a duplicate order against it -- which is
# exactly the false positive this case exists to rule out.
Add-Source -Root $prose -Module 'tenant-security' -Relative 'SecurityExceptionAdviceNotes.java' -Content @'
package com.firstelement.security.error;

/**
 * Notes on the security advice, kept beside it.
 *
 * <p>It is declared {@code @RestControllerAdvice} with no selectors. It used to be
 * {@code @RestControllerAdvice(basePackages = "com.firstelement")}, so a denial
 * raised outside that namespace was answered 500 instead of 404. Its order is
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE)}, ahead of the domain family and the
 * catch-all, and {@code @Order(Ordered.LOWEST_PRECEDENCE)} would be wrong for it.
 *
 * <pre>
 *   // @RestControllerAdvice
 *   // @Order(Ordered.LOWEST_PRECEDENCE)
 * </pre>
 */
public final class SecurityExceptionAdviceNotes {
}
'@ | Out-Null
Invoke-Case -Name 'javadoc quoting @RestControllerAdvice is not a declaration' `
    -Expectation 'exit 0 -- comments are not declarations' -ExpectedExit 0 `
    -ExpectContains @('advices found: 3', 'catch-all floors: 1', 'VERDICT: PASS') `
    -ExpectAbsent @('FE-ADVICE-001:', 'FE-ADVICE-002:', 'VERDICT: FAIL') `
    -FixtureRoot $prose

# --- 8. test-source advices are out of scope by default -------------
$fixtures = New-FixtureRoot 'test-fixtures'
Add-HealthyLadder -Root $fixtures
$testDir = New-QDirectory (Join-Path $fixtures 'backend\tenant-security\src\test\java\probe')
Set-Content -LiteralPath (Join-Path $testDir 'ProbeMirrorAdvice.java') -Encoding utf8 -Value @'
package probe;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE - 300)
@RestControllerAdvice
public class ProbeMirrorAdvice {

    @ExceptionHandler(RuntimeException.class)
    public String refused(RuntimeException ex) {
        return "refused";
    }
}
'@
Invoke-Case -Name 'a test fixture mirroring the floor is ignored by default' `
    -Expectation 'exit 0 -- advice order is only meaningful in one assembled application' -ExpectedExit 0 `
    -ExpectContains @('advices found: 3', 'VERDICT: PASS') `
    -FixtureRoot $fixtures

Invoke-Case -Name 'the same fixture is audited with -IncludeTestSources' `
    -Expectation 'exit 0 -- the fixture is ordered and holds no catch-all, so it is legal' -ExpectedExit 0 `
    -ExpectContains @('advices found: 4', 'catch-all floors: 1', 'VERDICT: PASS') `
    -IncludeTestSources `
    -FixtureRoot $fixtures

# --- 9. no advices at all fails loudly ------------------------------
# Zero advices means every unclaimed exception leaves the dispatcher as the
# container default: no FirstElement error body at all.
$empty = New-FixtureRoot 'no-advices'
Add-Source -Root $empty -Module 'shared-kernel' -Relative 'Thing.java' -Content @'
package com.firstelement.kernel;

public final class Thing {
}
'@ | Out-Null
Invoke-Case -Name 'no advices at all' `
    -Expectation 'exit 1 -- an exception nothing claims has no FirstElement body' -ExpectedExit 1 `
    -ExpectContains @('FE-ADVICE-004', 'no advice holds', 'VERDICT: FAIL') `
    -FixtureRoot $empty

# ---------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------

$verdict = if ($failures -eq 0) { 'PASS' } else { 'FAIL' }

$result = [ordered]@{
    stage        = 'advice-order-selftest'
    verdict      = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    checker      = $checker
    scratch      = $scratch
    caseCount    = $cases.Count
    failures     = $failures
    cases        = ConvertTo-QArray -Value $cases
}

if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

Write-QLog ''
Write-QLog ("advice-order selftest: {0}/{1} expectations held -- VERDICT {2}" -f ($cases.Count - $failures), $cases.Count, $verdict) -Level STEP
Write-QLog ("fixtures: {0}" -f $scratch) -Level INFO

# The fixtures are throwaway synthetic sources. They are left in place so a
# failing case can be read rather than guessed at, and because the directory
# name carries the run timestamp.
if ($failures -gt 0) { exit 1 }
exit 0
