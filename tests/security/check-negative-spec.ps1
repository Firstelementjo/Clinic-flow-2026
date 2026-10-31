#!/usr/bin/env pwsh
# =====================================================================
# check-negative-spec.ps1
#
# Validates tests/security/negative-spec.json -- plan.md section 7.2's
# sixteen critical negative tests -- against reality.
#
# The problem this solves: a security requirement recorded in a document
# is a wish. This check makes each entry answerable:
#
#   * every one of the sixteen plan.md 7.2 items has an entry (no gaps);
#   * an `enforced` entry names at least one suite, and every suite it
#     names exists in the expected-suite manifest -- so a suite that was
#     renamed or deleted breaks this gate instead of silently leaving a
#     requirement unenforced;
#   * when JUnit XML is supplied, every named test case must actually be in
#     the report, so a requirement cannot point at a test that was deleted
#     while the class kept running;
#   * a `staged` entry names an owning module, a phase and a gate stage,
#     so a gap is a scheduled debt rather than an omission;
#   * nothing is both enforced and staged.
#
# It deliberately does NOT re-implement any of the negative tests. They
# already exist inside the Spring modules and are referenced, not copied:
# a second copy would drift and a weakened copy would be worse than none.
#
# Exit codes: 0 consistent, 1 inconsistency, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $Spec,
    [string] $Manifest,
    # JUnit XML roots. When supplied, named test cases must be present.
    [string[]] $ResultsDir,
    [string] $JsonOut
)

$ErrorActionPreference = 'Stop'
$repoForLib = $null
for ($i = 0; $i -lt 5; $i++) {
    $candidate = Join-Path $PSScriptRoot (('..' + [string][char]92) * ($i + 1))
    if (Test-Path -LiteralPath (Join-Path $candidate 'settings.gradle.kts')) { $repoForLib = $candidate; break }
}
if (-not $repoForLib) { Write-QLog 'repository root not found' -Level ERROR; exit 2 }
. (Join-Path $repoForLib ('ops' + [string][char]92 + 'quality' + [string][char]92 + 'lib' + [string][char]92 + 'quality-common.ps1'))

# A child process invoked with `powershell -File script.ps1 -ResultsDir a,b`
# hands over ONE string, and a repeated -ResultsDir is a binding error
# before this script ever runs. Both spellings are therefore accepted:
# split on commas/semicolons as well as honour a real array.
function Split-QList {
    param([string[]] $Values)
    $out = @()
    if (-not $Values) { return $out }
    foreach ($v in $Values) {
        foreach ($piece in ([string]$v -split ';')) {
            foreach ($p in ($piece -split ',')) {
                $t = $p.Trim()
                if ($t) { $out += $t }
            }
        }
    }
    return $out
}
$ResultsDir = Split-QList -Values $ResultsDir
if ($ResultsDir.Count -eq 0) {
    Write-QLog '-ResultsDir is required' -Level ERROR
    exit 2
}
if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $Spec) { $Spec = Join-Path $RepoRoot 'tests\security\negative-spec.json' }
if (-not $Manifest) { $Manifest = Join-Path $RepoRoot 'tests\contracts\expected-suites.json' }

foreach ($p in @($Spec, $Manifest)) {
    if (-not (Test-Path -LiteralPath $p)) { Write-QLog ("missing: {0}" -f $p) -Level ERROR; exit 2 }
}

$specDoc = Get-Content -Raw -LiteralPath $Spec | ConvertFrom-Json
$manifestDoc = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json

$problems = New-Object System.Collections.Generic.List[string]
$entries  = New-Object System.Collections.Generic.List[object]

$manifestSuites = @{}
foreach ($s in @($manifestDoc.suites)) {
    # Both identifiers are registered: a @Nested suite is reported under its
    # @DisplayName, so a reference may legitimately use either.
    $manifestSuites[[string]$s.suite] = $s
    if (($s.PSObject.Properties.Name -contains 'reportName') -and ([string]$s.reportName)) {
        $manifestSuites[[string]$s.reportName] = $s
    }
}

# Observed test-case names, when results are supplied.
$observedCases = @{}
if ($ResultsDir -and $ResultsDir.Count -gt 0) {
    $junit = Get-QJUnitResults -Directories $ResultsDir
    foreach ($s in $junit.suites) {
        $suiteName = [string]$s.suite
        $observedCases[$suiteName] = @{}
    }
    # second pass: the per-suite records carry only skipped test names, so
    # re-read the XML for the full case list.
    foreach ($f in (Get-ChildItem -Path $ResultsDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'TEST-*.xml' })) {
        try { [xml] $caseDoc = Get-Content -Raw -LiteralPath $f.FullName } catch { continue }
        $suiteName = [string]$caseDoc.testsuite.name
        if (-not $observedCases.ContainsKey($suiteName)) { $observedCases[$suiteName] = @{} }
        foreach ($tc in @($caseDoc.testsuite.testcase)) {
            $observedCases[$suiteName][[string]$tc.name] = $true
        }
    }
}

# plan.md 7.2 wording, one identifier per item. A spec whose requirement
# does not match one of these is a paraphrase, and a paraphrase is how a
# requirement quietly changes meaning.
$planItems = @(
    'no membership returns no rows',
    'project ID from another tenant cannot be used for read or write',
    'unauthorized aggregates do not reveal counts or sums',
    'unauthorized rows/fields do not appear in CSV or DOCX',
    'export download reauthorizes and expires',
    'AI retrieval excludes another project even with a zero similarity threshold',
    'AI tool arguments cannot override tenant scope',
    'disabled tools are denied and recorded',
    'human takeover aborts an in-flight AI send',
    'duplicated webhooks create one message and one usage event',
    'worker crash after external send does not duplicate on retry',
    'outbox dispatch after commit does not publish rolled-back changes',
    'replay is safe and audited',
    'rate-card or period changes do not mutate closed snapshots',
    'realtime reconnect resyncs state instead of trusting missed events',
    'deleted knowledge is not retrievable after propagation completes'
)

$seen = @{}
$enforcedCount = 0
$stagedCount = 0

foreach ($e in @($specDoc.specs)) {
    $id = [string]$e.id
    $row = [ordered]@{
        id = $id; requirement = [string]$e.requirement; status = [string]$e.status
        suites = @(); namedTests = 0; ownerModule = [string]$e.ownerModule
        opensAtPhase = [string]$e.opensAtPhase; gateStage = [string]$e.gateStage
        problems = @()
    }
    $p = New-Object System.Collections.Generic.List[string]

    if (-not $id) { $p.Add('entry has no id') }
    if ($seen.ContainsKey($id)) { $p.Add(('duplicate id {0}' -f $id)) }
    $seen[$id] = $true

    if ($planItems -notcontains [string]$e.requirement) {
        $p.Add(('requirement text is not one of plan.md section 7.2 items verbatim: "{0}"' -f $e.requirement))
    }
    else {
        $planItems = @($planItems | Where-Object { $_ -ne [string]$e.requirement })
    }

    $status = [string]$e.status
    $hasEnforced = ($e.PSObject.Properties.Name -contains 'enforcedBy') -and (@($e.enforcedBy).Count -gt 0)
    $hasStaged = ($e.PSObject.Properties.Name -contains 'stagedBecause') -and ([string]$e.stagedBecause).Length -gt 0

    if ($status -eq 'enforced') {
        $enforcedCount++
        if (-not $hasEnforced) { $p.Add('status is enforced but no suite is referenced') }
        if ($hasStaged) { $p.Add('status is enforced but a stagedBecause is also present; an entry is either enforced or staged, never both') }
        foreach ($ref in @($e.enforcedBy)) {
            $suiteName = [string]$ref.suite
            $testName = [string]$ref.test
            $row.suites += $suiteName
            if (-not $suiteName) { $p.Add('an enforcedBy entry names no suite'); continue }
            if (-not $manifestSuites.ContainsKey($suiteName)) {
                $p.Add(('references suite "{0}" which is not in tests/contracts/expected-suites.json. Either the suite was renamed or it is not required; both break this requirement.' -f $suiteName))
            }
            if ($testName) {
                $row.namedTests++
                if ($observedCases.Count -gt 0) {
                    if (-not $observedCases.ContainsKey($suiteName)) {
                        $p.Add(('names test "{0}" in suite "{1}", but that suite produced no report in the supplied results' -f $testName, $suiteName))
                    }
                    elseif (-not $observedCases[$suiteName].ContainsKey($testName)) {
                        $p.Add(('names test "{0}" in suite "{1}", but that test case is not in the report' -f $testName, $suiteName))
                    }
                }
            }
        }
    }
    elseif ($status -eq 'staged') {
        $stagedCount++
        if (-not $hasStaged) { $p.Add('status is staged but no stagedBecause explains why') }
        if ($hasEnforced) { $p.Add('status is staged but enforcedBy is also present; an entry is either enforced or staged, never both') }
        if (-not $e.ownerModule) { $p.Add('status is staged but no ownerModule: a gap with no owner is an omission, not a debt') }
        if (-not $e.opensAtPhase) { $p.Add('status is staged but no opensAtPhase') }
        if (-not $e.gateStage) { $p.Add('status is staged but no gateStage: the stage that will enforce it must be named') }
    }
    else {
        $p.Add(('status must be enforced or staged, got "{0}"' -f $status))
    }

    $row.problems = ConvertTo-QArray -Value $p
    foreach ($x in $p) { $problems.Add(('{0}: {1}' -f $id, $x)) }
    $entries.Add($row)
}

foreach ($missing in $planItems) {
    $problems.Add(('plan.md section 7.2 item with no spec entry: "{0}"' -f $missing))
}

$verdict = if ($problems.Count -eq 0) { 'PASS' } else { 'FAIL' }

$namedTestTotal = 0
foreach ($row in $entries) { $namedTestTotal += [int]$row.namedTests }

$result = [ordered]@{
    check           = 'security-negative-spec'
    verdict         = $verdict
    checkedAtUtc    = (Get-Date).ToUniversalTime().ToString('o')
    spec            = $Spec
    manifest        = $Manifest
    resultsSupplied = ($ResultsDir.Count -gt 0)
    counts          = [ordered]@{
        planItems    = 16
        specEntries  = @($specDoc.specs).Count
        enforced     = $enforcedCount
        staged       = $stagedCount
        namedTests   = $namedTestTotal
    }
    entries         = ConvertTo-QArray -Value $entries
    problems        = ConvertTo-QArray -Value $problems
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 10 | Out-Null }

Write-QLog ('security negative spec: {0} enforced, {1} staged, {2} of plan.md 7.2 items covered' -f $enforcedCount, $stagedCount, @($specDoc.specs).Count) -Level STEP
foreach ($row in $entries) {
    $mark = if (@($row.problems).Count -eq 0) { 'ok  ' } else { 'FAIL' }
    Write-QLog ('  {0} {1,-7} {2,-9} {3}' -f $mark, $row.id, $row.status, $row.requirement)
}

if ($verdict -eq 'FAIL') {
    foreach ($x in $problems) { Write-QLog ('  - ' + $x) -Level ERROR }
    Write-QLog 'security-negative-spec VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'security-negative-spec VERDICT: PASS' -Level STEP
exit 0
