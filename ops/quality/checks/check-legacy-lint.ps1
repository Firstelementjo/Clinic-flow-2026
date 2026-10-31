#!/usr/bin/env pwsh
# =====================================================================
# check-legacy-lint.ps1
#
# Static analysis of the LEGACY Node/React runtime.
#
# The strangler migration still serves production from apps/** and
# packages/**. PROJECT_REQUIREMENTS section 2 records `yarn lint` at 118
# errors and 39 warnings and `yarn typecheck` failing; Phases 0-5a did not
# clear that debt and this gate does not pretend to.
#
# What it does instead is RATCHET it, which is the only honest option:
#
#   * `yarn typecheck` must PASS. It has no baseline: three implicit-any
#     parameters were the whole of the debt and Phase 0 fixed them.
#   * `yarn lint` must not get WORSE. The error and warning counts are
#     pinned in tests/contracts/lint-baseline.json and a count above the
#     pin FAILS. If a count falls, the pin should be tightened in the same
#     commit -- the gate says so; it never raises the pin itself.
#
# No rule is weakened, disabled or ignored to make this green. That is the
# whole difference between a ratchet and a suppression.
#
# Exit codes: 0 within baseline, 1 regression or typecheck failure,
# 2 bad invocation / tool missing.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $Baseline,
    [string] $JsonOut,
    # Report only; never exit non-zero. For a diagnostic run on a host
    # without Node. NOT accepted by the gate stage.
    [switch] $ReportOnly
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $Baseline) { $Baseline = Join-Path $RepoRoot 'tests\contracts\lint-baseline.json' }

if (-not (Test-Path -LiteralPath $Baseline)) {
    Write-QLog ("lint baseline missing: {0}" -f $Baseline) -Level ERROR
    exit 2
}
$base = Get-Content -Raw -LiteralPath $Baseline | ConvertFrom-Json

$problems = New-Object System.Collections.Generic.List[string]
$ps = Resolve-QPowerShell
$yarn = Resolve-QCommandPath -Name 'yarn'
if (-not $yarn) {
    Write-QLog 'yarn is not on PATH. A missing tool is a FAILED GATE, not a skip: the legacy runtime is still the production runtime.' -Level ERROR
    exit 1
}

# --- typecheck: no baseline, it must simply pass --------------------
$typecheckLog = Join-Path ([IO.Path]::GetTempPath()) ('fe-typecheck-' + (Get-QUtcTimestamp) + '.log')
Write-QLog '==> yarn typecheck' -Level STEP
$tc = Invoke-QProcess -Exe $yarn -Arguments @('typecheck') -WorkDir $RepoRoot -LogPath $typecheckLog -TimeoutMinutes 20
Write-QLog ("    exit {0}" -f $tc.exitCode)
$typecheckOk = ($tc.exitCode -eq 0)
if (-not $typecheckOk) {
    $problems.Add(('yarn typecheck FAILED (exit {0}). There is no baseline for typecheck: implicit any is a defect, not legacy debt. Log: {1}' -f $tc.exitCode, $typecheckLog))
}

# --- lint: ratcheted --------------------------------------------------
$lintLog = Join-Path ([IO.Path]::GetTempPath()) ('fe-lint-' + (Get-QUtcTimestamp) + '.log')
Write-QLog '==> yarn lint' -Level STEP
$lint = Invoke-QProcess -Exe $yarn -Arguments @('lint') -WorkDir $RepoRoot -LogPath $lintLog -TimeoutMinutes 30
$lintText = if (Test-Path -LiteralPath $lintLog) { Get-Content -Raw -LiteralPath $lintLog } else { '' }

$errors = -1
$warnings = -1
# The summary line is matched WITHOUT its leading symbol. ESLint's glyph
# (U+2716) survives a round trip through Out-File/Out-File differently on
# different hosts and code pages, and a gate whose parser depends on a
# decorative character is a gate that fails on someone's machine and not on
# someone else's. `157 problems (118 errors, 39 warnings)` is unambiguous on
# its own.
$m = [regex]::Match($lintText, '(?m)\b(?<p>\d+)\s+problems?\s*\(\s*(?<e>\d+)\s+errors?\s*,\s*(?<w>\d+)\s+warnings?\s*\)')
if ($m.Success) {
    $errors = [int]$m.Groups['e'].Value
    $warnings = [int]$m.Groups['w'].Value
}
elseif ($lint.exitCode -eq 0) {
    $errors = 0; $warnings = 0
}
else {
    $problems.Add(('could not parse an eslint summary line out of `yarn lint` (exit {0}). The gate cannot judge a count it cannot read, so it fails. Log: {1}' -f $lint.exitCode, $lintLog))
}

$baseErrors = [int]$base.errors
$baseWarnings = [int]$base.warnings
if ($errors -ge 0) {
    Write-QLog ("    errors {0} (baseline {1})   warnings {2} (baseline {3})" -f $errors, $baseErrors, $warnings, $baseWarnings)
    if ($errors -gt $baseErrors) {
        $problems.Add(('lint REGRESSION: {0} errors, the pinned ceiling is {1}. Fix the new errors; do not raise the pin to accommodate them.' -f $errors, $baseErrors))
    }
    if ($warnings -gt $baseWarnings) {
        $problems.Add(('lint REGRESSION: {0} warnings, the pinned ceiling is {1}.' -f $warnings, $baseWarnings))
    }
    if (($errors -lt $baseErrors) -or ($warnings -lt $baseWarnings)) {
        Write-QLog ("    IMPROVEMENT: the pin can be tightened to errors={0} warnings={1} in tests/contracts/lint-baseline.json" -f $errors, $warnings) -Level WARN
    }
}

$verdict = if ($problems.Count -eq 0) { 'PASS' } else { 'FAIL' }
$result = [ordered]@{
    check        = 'legacy-node-static'
    verdict      = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    typecheck    = [ordered]@{ exitCode = $tc.exitCode; ok = $typecheckOk; log = $typecheckLog }
    lint         = [ordered]@{ exitCode = $lint.exitCode; errors = $errors; warnings = $warnings; log = $lintLog }
    baseline     = [ordered]@{ errors = $baseErrors; warnings = $baseWarnings; recordedAt = [string]$base.recordedAt }
    problems     = ConvertTo-QArray -Value $problems
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

if ($ReportOnly) {
    Write-QLog ('legacy-node-static: typecheck {0}, lint errors {1}/{2}, warnings {3}/{4}' -f $tc.exitCode, $errors, $baseErrors, $warnings, $baseWarnings)
    exit 0
}
if ($verdict -eq 'FAIL') {
    foreach ($p in $problems) { Write-QLog ('  - ' + $p) -Level ERROR }
    Write-QLog 'legacy-node-static VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'legacy-node-static VERDICT: PASS -- typecheck clean and lint within the pinned ceiling' -Level STEP
exit 0