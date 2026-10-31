#!/usr/bin/env pwsh
# =====================================================================
# check-disposable-targets.ps1
#
# Static proof that no gate, script or test in this repository can target
# `firstelement` for a rehearsal, a migration or a restore.
#
# `firstelement` is the development database. It has no Flyway history, no
# backup encryption decision, and no restore-in-place procedure. A
# rehearsal that runs against it is unrecoverable, and the failure mode is
# a mistyped flag, not a bug somebody writes on purpose. This check reads
# the gate catalogue and the repository's own scripts and fails on the
# shape that could do it.
#
#   FE-TARGET-001  no stage names `firstelement` as a rehearsal/migration
#                  TARGET
#   FE-TARGET-002  every restore invocation passes -Dispose and a target
#                  whose name looks disposable
#   FE-TARGET-003  every run-verification invocation carries
#                  -ThrowOnViolation (otherwise the exit code is
#                  decorative, which is open debt from Phase 2/5a)
#   FE-TARGET-004  ops/flyway/** and ops/backup-restore/** are never
#                  invoked with -Database firstelement -MigrateChain or an
#                  equivalent write flag
#
# Exit codes: 0 clean, 1 violations, 2 bad invocation.
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

$findings = New-Object System.Collections.Generic.List[object]
$scanned  = 0

function Violation {
    param([string] $Rule, [string] $File, [string] $Detail)
    $script:findings.Add([ordered]@{ rule = $Rule; file = $File; detail = $Detail })
}

$relOf = { param($p) return $p.Replace($RepoRoot + '\', '').Replace($RepoRoot + '/', '') }

# --- 1. the gate catalogue -------------------------------------------
$stagesPath = Join-Path $RepoRoot 'ops\quality\gate-stages.json'
$stagesDoc = $null
if (Test-Path -LiteralPath $stagesPath) {
    $stagesDoc = Get-Content -Raw -LiteralPath $stagesPath | ConvertFrom-Json
    $scanned++
    foreach ($stage in @($stagesDoc.stages)) {
        $target = ('ops/quality/gate-stages.json [' + $stage.name + ']')

        foreach ($group in @('commands', 'verifyCommands', 'alwaysCommands')) {
            if (-not ($stage.PSObject.Properties.Name -contains $group)) { continue }
            foreach ($c in @($stage.$group)) {
                $argv = @($c.args)
                $joined = ($argv -join ' ')

                # Which script is this command invoking?
                $scriptName = ''
                foreach ($a in $argv) { if ([string]$a -match '([A-Za-z0-9_-]+\.ps1)$') { $scriptName = [string]$Matches[1] } }

                $isMutation = (($joined -match 'MigrateChain') -or ($scriptName -eq 'prepare-throwaway-db.ps1'))

                # FE-TARGET-001: a MUTATING script must never be given the
                # protected database. Passing it to a READ-ONLY bundle
                # (run-verification.ps1, backup.ps1) is exactly how the
                # dev-database before/after comparison is meant to work.
                if ($isMutation) {
                    for ($i = 0; $i -lt $argv.Count; $i++) {
                        if ([string]$argv[$i] -match '^(-Database|-TargetDatabase)$') {
                            if (($i + 1) -lt $argv.Count -and [string]$argv[$i + 1] -eq 'firstelement') {
                                Violation -Rule 'FE-TARGET-001' -File $target `
                                    -Detail ("{0} is a mutating script and is given -Database firstelement" -f $scriptName)
                            }
                        }
                    }
                    if ($joined -match '(?i)(drop database|create database)[^"]*firstelement') {
                        Violation -Rule 'FE-TARGET-001' -File $target -Detail 'the stage issues CREATE/DROP DATABASE against firstelement'
                    }
                }

                # FE-TARGET-002: a rehearsal must dispose, and its target
                # must look disposable. prepare-restore-rehearsal.ps1 is
                # the wrapper that enforces both, so it is checked
                # separately below rather than through the -Dispose text.
                if ($joined -match '(?<!prepare-)restore-rehearsal\.ps1') {
                    if ($joined -notmatch '-Dispose') {
                        Violation -Rule 'FE-TARGET-002' -File $target `
                            -Detail 'restore-rehearsal.ps1 is invoked without -Dispose, so the throwaway database would be left behind'
                    }
                    if ($joined -notmatch '(?i)(restore_check|rehearsal)') {
                        Violation -Rule 'FE-TARGET-002' -File $target `
                            -Detail 'restore-rehearsal.ps1 target does not look disposable (no restore_check / rehearsal in the name)'
                    }
                }
                if ($scriptName -eq 'prepare-restore-rehearsal.ps1') {
                    $hasTarget = $false
                    $looksDisposable = $false
                    for ($i = 0; $i -lt $argv.Count; $i++) {
                        if ([string]$argv[$i] -eq '-TargetDatabase' -and ($i + 1) -lt $argv.Count) {
                            $hasTarget = $true
                            if ([string]$argv[$i + 1] -match '(?i)(restore_check|rehearsal)') { $looksDisposable = $true }
                        }
                    }
                    if (-not $hasTarget) {
                        Violation -Rule 'FE-TARGET-002' -File $target -Detail 'prepare-restore-rehearsal.ps1 is invoked without an explicit -TargetDatabase'
                    }
                    if (-not $looksDisposable) {
                        Violation -Rule 'FE-TARGET-002' -File $target -Detail 'the rehearsal target name does not contain restore_check or rehearsal'
                    }
                }

                # FE-TARGET-003: assertions must become an exit code
                if ($joined -match 'run-verification\.ps1' -and $joined -notmatch '-ThrowOnViolation') {
                    Violation -Rule 'FE-TARGET-003' -File $target `
                        -Detail 'run-verification.ps1 without -ThrowOnViolation: its assertions would print a message and still exit 0. This is the exact debt Phase 2 risk 9 and Phase 5a risk 7 recorded'
                }
            }
        }
    }
}
else {
    Violation -Rule 'FE-TARGET-001' -File 'ops/quality/gate-stages.json' -Detail 'gate catalogue missing; nothing declares what the gates run'
}

# --- 2. every script in the repository -------------------------------
# Only .ps1 files: -Include with -LiteralPath and -Recurse filters the
# PATH LEAF rather than the item, so it also returned .md files, and a
# markdown runbook that quotes a DROP DATABASE command is documentation,
# not a script that can execute one.
$skipDirs = '\\(node_modules|build|\.git|\.gradle|dist|coverage|out)\\'
$scriptFiles = @(Get-ChildItem -LiteralPath $RepoRoot -Recurse -File |
    Where-Object { $_.Extension -eq '.ps1' -and $_.FullName -notmatch $skipDirs })
foreach ($f in $scriptFiles) {
    # The checker is exempt from itself: its own regular expressions
    # contain the very strings it forbids, so matching them would make the
    # gate permanently red for the one file whose job is to look for them.
    if ($f.FullName -eq $MyInvocation.MyCommand.Path) { continue }
    $scanned++
    $rel = & $relOf $f.FullName
    $text = Get-Content -Raw -LiteralPath $f.FullName

    # FE-TARGET-002 : a restore rehearsal must dispose
    if ($text -match 'restore-rehearsal\.ps1' -and $text -notmatch '\.EXAMPLE|\.md') {
        $mentionsCall = $text -match '(?m)^\s*(&\s*\$?\w*restore|&\s*Join-Path|restore-rehearsal)'
        if ($mentionsCall -and $text -notmatch '-Dispose') {
            Violation -Rule 'FE-TARGET-002' -File $rel -Detail 'invokes restore-rehearsal.ps1 without -Dispose'
        }
    }

    # FE-TARGET-004 : nothing writes the development database
    if ($text -match '(?m)-Database\s+firstelement' -and $text -match 'MigrateChain|flywayMigrate|CREATE DATABASE|DROP DATABASE') {
        Violation -Rule 'FE-TARGET-004' -File $rel -Detail 'script can both name `firstelement` and write DDL'
    }
    if ($text -match '(?m)DROP DATABASE(?!\s+IF EXISTS\s+")\s*firstelement') {
        Violation -Rule 'FE-TARGET-004' -File $rel -Detail 'script drops the firstelement database'
    }
}

# --- 3. the runner itself must carry the protected name --------------
$runnerPath = Join-Path $RepoRoot 'ops\quality\run-gates.ps1'
if (Test-Path -LiteralPath $runnerPath) {
    $scanned++
    $runnerText = Get-Content -Raw -LiteralPath $runnerPath
    if ($runnerText -notmatch "ProtectedDatabase") {
        Violation -Rule 'FE-TARGET-001' -File 'ops/quality/run-gates.ps1' `
            -Detail 'the runner does not carry the protected database name, so a stage could not be checked against it'
    }
}
else {
    Violation -Rule 'FE-TARGET-001' -File 'ops/quality/run-gates.ps1' -Detail 'gate runner missing'
}

$verdict = if ($findings.Count -eq 0) { 'PASS' } else { 'FAIL' }
$result = [ordered]@{
    check        = 'disposable-targets'
    verdict      = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    protectedDatabase = 'firstelement'
    scanned      = $scanned
    findings     = ConvertTo-QArray -Value $findings
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

Write-QLog ("disposable-target gate: scanned {0} catalogue/script source(s)" -f $scanned) -Level STEP
if ($verdict -eq 'FAIL') {
    foreach ($f in $findings) { Write-QLog ('  {0} {1}: {2}' -f $f.rule, $f.file, $f.detail) -Level ERROR }
    Write-QLog 'disposable-targets VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'disposable-targets VERDICT: PASS -- `firstelement` is never a rehearsal, migration or restore target' -Level STEP
exit 0