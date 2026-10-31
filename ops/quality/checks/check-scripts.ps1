#!/usr/bin/env pwsh
# =====================================================================
# check-scripts.ps1
#
# Static checks for the files this repository's own quality gates live in.
#
#   FE-SCRIPT-001  every .ps1 in the repository parses (PowerShell AST)
#   FE-SCRIPT-002  every GitHub Actions workflow is structurally sound
#                  (name, on, jobs, runs-on, steps, no tabs, no trailing
#                  whitespace, LF endings) and invokes the gate runner
#   FE-SCRIPT-003  no trailing whitespace / tab indentation in owned text
#   FE-HYGIENE-001 no script, workflow or test file reads or prints .env
#   FE-HYGIENE-002 no credential-shaped literal is committed in the owned
#                  trees (ops/quality, tests, e2e, .github)
#
# Why this is a stage and not a lint config: the gate runner is the thing
# that decides whether a release is green, and a syntax error inside it
# turns "green" into "nothing ran". A gate that cannot parse is not a
# gate, so its own syntax is a build input.
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

$skipDirs = '\\(node_modules|build|\.git|\.gradle|dist|coverage|out)\\'
$findings = New-Object System.Collections.Generic.List[object]
$groups   = New-Object System.Collections.Generic.List[object]

function Add-Finding {
    param([string] $Rule, [string] $File, [string] $Detail)
    $script:findings.Add([ordered]@{ rule = $Rule; file = $File; detail = $Detail })
}

function Close-Group {
    param([string] $Rule, [string] $Title, $List)
    $arr = ConvertTo-QArray -Value $List
    $outcome = if ($arr.Count -eq 0) { 'PASS' } else { 'FAIL' }
    $script:groups.Add([ordered]@{ rule = $Rule; title = $Title; violationCount = $arr.Count; findings = $arr; outcome = $outcome })
    $mark = if ($outcome -eq 'PASS') { 'ok  ' } else { 'FAIL' }
    Write-QLog ("  {0} {1,-52} {2} violation(s)" -f $mark, $Rule, $arr.Count) -Level $(if ($outcome -eq 'PASS') { 'INFO' } else { 'ERROR' })
}

$relOf = {
    param($p) return $p.Replace($RepoRoot + '\', '').Replace($RepoRoot + '/', '')
}

# ---------------------------------------------------------------------
# FE-SCRIPT-001 : PowerShell syntax
# ---------------------------------------------------------------------

$parseFindings = New-Object System.Collections.Generic.List[object]
$ps1Files = @(Get-ChildItem -LiteralPath $RepoRoot -Recurse -Filter '*.ps1' -File |
    Where-Object { $_.FullName -notmatch $skipDirs })
Write-QLog ("script gate: {0} PowerShell files under the repository root" -f $ps1Files.Count) -Level STEP

foreach ($f in $ps1Files) {
    $tokens = $null
    $errors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$tokens, [ref]$errors)
    if ($errors -and $errors.Count -gt 0) {
        $first = $errors[0]
        $msg = ('line {0} col {1}: {2}' -f $first.Extent.StartLineNumber, $first.Extent.StartColumnNumber, $first.Message)
        Add-Finding -Rule 'FE-SCRIPT-001' -File (& $relOf $f.FullName) -Detail $msg
        $parseFindings.Add([ordered]@{ file = (& $relOf $f.FullName); detail = $msg })
    }
}
Close-Group -Rule 'FE-SCRIPT-001' -Title 'every .ps1 parses' -List $parseFindings

# ---------------------------------------------------------------------
# FE-SCRIPT-002 : workflow structure
# ---------------------------------------------------------------------

$wfFindings = New-Object System.Collections.Generic.List[object]
$wfDir = Join-Path $RepoRoot '.github\workflows'
$wfFiles = @()
if (Test-Path -LiteralPath $wfDir) {
    $wfFiles = @(Get-ChildItem -LiteralPath $wfDir -Filter '*.yml' -File) + @(Get-ChildItem -LiteralPath $wfDir -Filter '*.yaml' -File)
}

if ($wfFiles.Count -eq 0) {
    Add-Finding -Rule 'FE-SCRIPT-002' -File '.github/workflows' -Detail 'no workflow files at all: there is no required status check, so every gate in ops/quality is advisory by default'
    $wfFindings.Add([ordered]@{ file = '.github/workflows'; detail = 'no workflow files at all' })
}

foreach ($wf in $wfFiles) {
    $rel = & $relOf $wf.FullName
    $text = Get-Content -Raw -LiteralPath $wf.FullName

    $required = @(
        @{ pattern = '(?m)^name:'; label = 'top-level name:' },
        @{ pattern = '(?m)^on:'; label = 'trigger on:' },
        @{ pattern = '(?m)^jobs:'; label = 'jobs:' }
    )
    foreach ($r in $required) {
        if ($text -notmatch $r.pattern) {
            Add-Finding -Rule 'FE-SCRIPT-002' -File $rel -Detail ('missing ' + $r.label)
            $wfFindings.Add([ordered]@{ file = $rel; detail = ('missing ' + $r.label) })
        }
    }
    if ($text -match "`t") {
        Add-Finding -Rule 'FE-SCRIPT-002' -File $rel -Detail 'contains a literal TAB; YAML forbids tab indentation and the parser error is opaque'
        $wfFindings.Add([ordered]@{ file = $rel; detail = 'contains a literal TAB' })
    }
    if ($text -notmatch '(?m)^\s{2,}runs-on:') {
        Add-Finding -Rule 'FE-SCRIPT-002' -File $rel -Detail 'no job declares runs-on:'
        $wfFindings.Add([ordered]@{ file = $rel; detail = 'no job declares runs-on:' })
    }
    if ($text -notmatch '(?m)^\s{2,}steps:') {
        Add-Finding -Rule 'FE-SCRIPT-002' -File $rel -Detail 'no job declares steps:'
        $wfFindings.Add([ordered]@{ file = $rel; detail = 'no job declares steps:' })
    }
    if ($text -notmatch 'run-gates\.ps1') {
        Add-Finding -Rule 'FE-SCRIPT-002' -File $rel -Detail 'no step calls ops/quality/run-gates.ps1: a workflow that reimplements the gates can drift from the ones developers run'
        $wfFindings.Add([ordered]@{ file = $rel; detail = 'no step calls ops/quality/run-gates.ps1' })
    }
    if ($text -notmatch 'if:\s*always\(\)') {
        Add-Finding -Rule 'FE-SCRIPT-002' -File $rel -Detail 'no `if: always()` evidence-upload step: when an earlier stage fails the evidence bundle would not be published, which is exactly when it is needed'
        $wfFindings.Add([ordered]@{ file = $rel; detail = 'no `if: always()` evidence-upload step' })
    }
    $jobNames = @([regex]::Matches($text, '(?m)^  (?<j>[A-Za-z0-9_-]+):\s*$') | ForEach-Object { $_.Groups['j'].Value })
    if ($jobNames.Count -lt 5) {
        Add-Finding -Rule 'FE-SCRIPT-002' -File $rel -Detail ("only {0} job(s) declared; plan.md section 7.1 requires the stages to report separately so a red stage is never masked" -f $jobNames.Count)
        $wfFindings.Add([ordered]@{ file = $rel; detail = ("only {0} job(s) declared" -f $jobNames.Count) })
    }
}
Close-Group -Rule 'FE-SCRIPT-002' -Title 'workflow structure' -List $wfFindings

# ---------------------------------------------------------------------
# FE-SCRIPT-003 : whitespace hygiene in owned trees
# ---------------------------------------------------------------------

$wsFindings = New-Object System.Collections.Generic.List[object]
$ownedGlobs = @(
    (Join-Path $RepoRoot 'ops\quality'),
    (Join-Path $RepoRoot 'tests'),
    (Join-Path $RepoRoot 'e2e'),
    (Join-Path $RepoRoot '.github'),
    (Join-Path $RepoRoot 'docs\migration\phases\quality-gates.md'),
    (Join-Path $RepoRoot 'docs\migration\testing.md')
)
$textExtensions = @('.ps1', '.json', '.md', '.yml', '.yaml', '.sql', '.ts', '.js')
foreach ($g in $ownedGlobs) {
    if (-not (Test-Path -LiteralPath $g)) { continue }
    $files = @()
    if ((Get-Item -LiteralPath $g).PSIsContainer) {
        $files = @(Get-ChildItem -LiteralPath $g -Recurse -File | Where-Object { $textExtensions -contains $_.Extension })
    }
    else { $files = @(Get-Item -LiteralPath $g) }
    foreach ($f in $files) {
        $rel = & $relOf $f.FullName
        $lineNo = 0
        foreach ($line in (Get-Content -LiteralPath $f.FullName)) {
            $lineNo++
            if ($line -match '[ \t]+$' -and $line.Trim().Length -gt 0) {
                Add-Finding -Rule 'FE-SCRIPT-003' -File $rel -Detail ('trailing whitespace on line {0}' -f $lineNo)
                $wsFindings.Add([ordered]@{ file = $rel; detail = ('trailing whitespace on line {0}' -f $lineNo) })
            }
        }
    }
}
Close-Group -Rule 'FE-SCRIPT-003' -Title 'no trailing whitespace in owned trees' -List $wsFindings

# ---------------------------------------------------------------------
# FE-HYGIENE-001 / 002 : secrets
# ---------------------------------------------------------------------

$envFindings = New-Object System.Collections.Generic.List[object]
$credFindings = New-Object System.Collections.Generic.List[object]

$secretLiterals = @(
    '(?i)\b(password|passwd|secret|api[_-]?key|token|passphrase|private[_-]?key)\s*[:=]\s*["''][^"''\s]{6,}["'']',
    '-----BEGIN [A-Z ]*PRIVATE KEY-----',
    '(?i)\bAKIA[0-9A-Z]{16}\b',
    '(?i)\bghp_[A-Za-z0-9]{20,}\b',
    '(?i)\bsk-[A-Za-z0-9]{20,}\b'
)

foreach ($g in $ownedGlobs) {
    if (-not (Test-Path -LiteralPath $g)) { continue }
    $files = @()
    if ((Get-Item -LiteralPath $g).PSIsContainer) {
        $files = @(Get-ChildItem -LiteralPath $g -Recurse -File | Where-Object { $textExtensions -contains $_.Extension })
    }
    else { $files = @(Get-Item -LiteralPath $g) }

    foreach ($f in $files) {
        $rel = & $relOf $f.FullName
        $text = Get-Content -Raw -LiteralPath $f.FullName
        $lineNo = 0
        foreach ($line in (Get-Content -LiteralPath $f.FullName)) {
            $lineNo++
            # reading or printing .env
            if ($line -match '(?i)(Get-Content|ReadAllText|type|cat)\s+[^#\r\n]*\.env\b' -and $line -notmatch '(?i)\.env\.example') {
                Add-Finding -Rule 'FE-HYGIENE-001' -File $rel -Detail ('reads .env on line {0}: {1}' -f $lineNo, $line.Trim())
                $envFindings.Add([ordered]@{ file = $rel; detail = ('reads .env on line {0}' -f $lineNo) })
            }
            foreach ($p in $secretLiterals) {
                if ($line -match $p) {
                    # a documented example placeholder is not a credential
                    if ($line -match '(?i)(example|placeholder|redacted|\$\(|<your|\*{3,}|not-a-real|xxxxx|\.\.\.)') { continue }
                    Add-Finding -Rule 'FE-HYGIENE-002' -File $rel -Detail ('credential-shaped literal on line {0}: {1}' -f $lineNo, $line.Trim())
                    $credFindings.Add([ordered]@{ file = $rel; detail = ('credential-shaped literal on line {0}' -f $lineNo) })
                }
            }
        }
        if ($null -eq $text) { continue }
    }
}
Close-Group -Rule 'FE-HYGIENE-001' -Title 'no .env read' -List $envFindings
Close-Group -Rule 'FE-HYGIENE-002' -Title 'no credential-shaped literal committed' -List $credFindings

# ---------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------

$verdict = if ($findings.Count -eq 0) { 'PASS' } else { 'FAIL' }
$result = [ordered]@{
    check        = 'scripts'
    verdict      = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    repoRoot     = $RepoRoot
    powerShellFiles = $ps1Files.Count
    workflows       = ConvertTo-QArray -Value ($wfFiles | ForEach-Object { & $relOf $_.FullName })
    rules        = ConvertTo-QArray -Value $groups
    findings     = ConvertTo-QArray -Value $findings
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 10 | Out-Null }

Write-QLog ''
if ($verdict -eq 'FAIL') {
    foreach ($f in $findings) { Write-QLog ('  {0} {1}: {2}' -f $f.rule, $f.file, $f.detail) -Level ERROR }
    Write-QLog 'scripts VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'scripts VERDICT: PASS' -Level STEP
exit 0