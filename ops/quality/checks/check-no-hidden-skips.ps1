#!/usr/bin/env pwsh
# =====================================================================
# check-no-hidden-skips.ps1
#
# The gate that makes every other gate honest.
#
# A skipped integration, security, browser, migration or restore test is
# a FAILURE (AGENTS.md "Quality rules"; plan.md rule 10; plan.md section 10
# "CI has no hidden skipped integration/security/browser tests"). This
# script is the mechanical enforcement of that rule.
#
# It FAILS when:
#   1. the results directories contain no JUnit XML at all
#      (a build that ran nothing must never look green);
#   2. any testsuite reports skipped > 0 (attribute or <skipped/> element);
#   3. the attribute count and the element count disagree
#      (a mis-reported skip is worse than a reported one);
#   4. a suite required by the manifest is missing entirely from the
#      results for a module that was in scope;
#   5. a required suite ran but produced fewer tests than its floor
#      (a gutted suite that still "passes");
#   6. a JUnit XML cannot be parsed;
#   7. an allow-list entry is malformed, blanket, expired, or unused.
#
# The allow-list mechanism (ops/quality/waivers/no-hidden-skips.allow.json):
#   Every entry names the exact suite AND the exact test case, a reason,
#   a ticket and an expiry date. A wildcard in either field is rejected
#   outright -- there is no blanket waiver, by construction. An entry that
#   matches nothing FAILS, because a stale waiver is how a temporary
#   exception quietly becomes permanent.
#
# Exit codes: 0 = clean, 1 = violations found, 2 = bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    # One or more directories searched recursively for TEST-*.xml.
    [Parameter(Mandatory = $true)]
    [string[]] $ResultsDir,

    # Required-suite manifest. A manifest entry whose module is not in
    # -ModuleScope is reported as out-of-scope rather than failed, so a
    # developer can run one stage and still get a meaningful answer.
    [string] $Manifest,

    # Backend modules whose results directories were expected to contain
    # results. Typically the modules of the stages that just ran.
    [string[]] $ModuleScope,

    [string] $AllowList,

    # Label recorded in the evidence bundle.
    [string] $Stage = 'no-hidden-skips',

    [string] $JsonOut,

    # Overridable clock so the expiry logic can be tested deterministically
    # instead of waiting for a date to pass.
    [string] $Now
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

# A child process invoked with `powershell -File script.ps1 -ResultsDir a,b`
# hands over ONE string, and a repeated -ResultsDir is a binding error before
# this script ever runs. Both spellings are therefore accepted.
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
if ($ResultsDir.Count -eq 0) { Write-QLog '-ResultsDir is required' -Level ERROR; exit 2 }

if (-not $Now) { $Now = (Get-Date).ToUniversalTime().ToString('o') }
$nowUtc = $null
try   { $nowUtc = ConvertTo-QUtcDate -Value $Now }
catch {
    Write-QLog "Invalid -Now '$Now': $($_.Exception.Message)" -Level ERROR
    exit 2
}

$violations = New-Object System.Collections.Generic.List[string]
$notes      = New-Object System.Collections.Generic.List[string]
$allowUsed  = @{}

function Add-Violation {
    param([string] $Message)
    $script:violations.Add($Message)
}

function Add-Note {
    param([string] $Message)
    $script:notes.Add($Message)
}

# ---------------------------------------------------------------------
# 1. Parse the results
# ---------------------------------------------------------------------

Write-QLog "[$Stage] scanning JUnit XML under: $($ResultsDir -join ', ')" -Level STEP
$junit = Get-QJUnitResults -Directories $ResultsDir
$t = $junit.totals

Write-QLog ("[{0}] {1} suites, {2} tests, {3} failures, {4} errors, skipped(attr)={5}, skipped(elements)={6}" -f `
        $Stage, $t.files, $t.tests, $t.failures, $t.errors, $t.skippedAttribute, $t.skippedElements)

foreach ($pe in $junit.parseErrors) {
    $m = 'JUnit XML could not be parsed: ' + $pe
    Add-Violation $m
}

if ($junit.resultsEmpty) {
    $m = ('No TEST-*.xml files were found under: {0}. ' -f ($ResultsDir -join ', ')) +
         'A stage that produced no results has NOT passed; it has not run. ' +
         'This usually means the Gradle task was UP-TO-DATE (build cache) or the ' +
         'module was never compiled -- re-run with --rerun-tasks --no-build-cache.'
    Add-Violation $m
}

# ---------------------------------------------------------------------
# 2. Allow-list: parse and validate BEFORE trusting it for anything
# ---------------------------------------------------------------------

$allowEntries = @()
if ($AllowList) {
    if (-not (Test-Path -LiteralPath $AllowList)) {
        $m = 'Allow-list file not found: ' + $AllowList
        Add-Violation $m
    }
    else {
        $allowDoc = $null
        try { $allowDoc = Get-Content -Raw -LiteralPath $AllowList | ConvertFrom-Json }
        catch {
            $m = 'Allow-list is not valid JSON: ' + $AllowList + ' -- ' + $_.Exception.Message
            Add-Violation $m
        }
        if ($allowDoc) {
            if ($allowDoc.PSObject.Properties.Name -contains 'entries' -and $null -ne $allowDoc.entries) {
                $allowEntries = @($allowDoc.entries)
            }
            else {
                $m = 'Allow-list has no "entries" array: ' + $AllowList +
                     ' (an empty list must be written as "entries": [], not as a missing key)'
                Add-Violation $m
            }
        }
    }
}

$allowValid = New-Object System.Collections.Generic.List[object]
foreach ($e in $allowEntries) {
    $suiteId = [string]$e.suite
    $testId  = [string]$e.test
    $problems = New-Object System.Collections.Generic.List[string]

    if (-not $suiteId) { $problems.Add('missing "suite"') }
    if (-not $testId)  { $problems.Add('missing "test"') }

    if ($suiteId -eq '*' -or $testId -eq '*' -or $suiteId.Contains('*') -or $testId.Contains('*')) {
        $problems.Add('a wildcard in "suite" or "test" is a blanket waiver and is never accepted')
    }
    if ($suiteId -notlike '*.*') {
        $problems.Add('"suite" must be a fully-qualified class name, not a display name or a pattern')
    }
    if (-not $e.reason -or ([string]$e.reason).Trim().Length -lt 10) {
        $problems.Add('"reason" must be at least 10 characters of actual justification')
    }
    if (-not $e.ticket -or ([string]$e.ticket).Trim().Length -lt 3) {
        $problems.Add('"ticket" is required (the tracker item that will remove this skip)')
    }

    $expiryOk = $true
    $expiry = $null
    if (-not $e.expiresOn) {
        $problems.Add('"expiresOn" is required; a skip waiver with no expiry is a permanent waiver')
        $expiryOk = $false
    }
    else {
        try { $expiry = ConvertTo-QUtcDate -Value ([string]$e.expiresOn) }
        catch {
            $problems.Add('"expiresOn" is not a date: ' + [string]$e.expiresOn)
            $expiryOk = $false
        }
        if ($expiryOk -and $expiry -le $nowUtc) {
            $problems.Add(('EXPIRED on {0}; renew it with a new ticket or remove the skip' -f $expiry.ToString('yyyy-MM-dd')))
        }
        if ($expiryOk -and ($expiry - $nowUtc).TotalDays -gt 30) {
            $problems.Add('an expiry more than 30 days out is not a waiver, it is a postponement; cap it at 30 days')
        }
    }

    if ($problems.Count -gt 0) {
        $m = ("Allow-list entry '{0} / {1}' is invalid: {2}" -f $suiteId, $testId, ($problems -join '; '))
        Add-Violation $m
    }
    else {
        $allowValid.Add([pscustomobject]@{ key = ($suiteId + '|' + $testId); entry = $e })
    }
}

# ---------------------------------------------------------------------
# 3. Skips
# ---------------------------------------------------------------------

$skippedObserved = New-Object System.Collections.Generic.List[object]
$waivedCount = 0

foreach ($suite in $junit.suites) {
    $attr = [int]$suite.skipped
    $els  = [int]$suite.skippedElements

    if ($attr -ne $els) {
        $m = ("Suite '{0}' reports skipped={1} but contains {2} <skipped/> element(s). " -f $suite.suite, $attr, $els) +
             'The report is lying about its own skips, which is worse than a skip: fix the ' +
             'reporting, do not reconcile the numbers by ignoring one of them.'
        Add-Violation $m
    }

    if ($attr -eq 0 -and $els -eq 0) { continue }

    $names = New-Object System.Collections.Generic.List[string]
    foreach ($n in $suite.skippedTests) { if ($n) { $names.Add([string]$n) } }

    foreach ($n in $names) {
        $key = $suite.suite + '|' + $n
        $hit = $allowValid | Where-Object { $_.key -eq $key }
        if ($hit) {
            $allowUsed[$key] = $true
            $script:waivedCount++
            $m = ("SKIP WAIVED: {0} :: {1} -- reason: {2} (ticket {3}, expires {4})" -f `
                    $suite.suite, $n, $hit.entry.reason, $hit.entry.ticket, $hit.entry.expiresOn)
            Add-Note $m
        }
        else {
            $skippedObserved.Add([pscustomobject]@{ suite = $suite.suite; test = $n; file = $suite.file })
        }
    }
}

foreach ($s in $skippedObserved) {
    $m = ("SKIPPED TEST (not waived): {0} :: {1}   [{2}]" -f $s.suite, $s.test, (Split-Path -Leaf $s.file)) +
         "`n    A skipped test is a failed gate. Fix the environment, delete the assumption, " +
         'or -- only with a ticket -- record an expiring entry in ' +
         'ops/quality/waivers/no-hidden-skips.allow.json.'
    Add-Violation $m
}

foreach ($a in $allowValid) {
    if (-not $allowUsed.ContainsKey($a.key)) {
        $m = ("STALE WAIVER: {0} :: {1} matched no skipped test. " -f $a.entry.suite, $a.entry.test) +
             'Remove it; a waiver nobody exercises is how temporary exceptions become permanent.'
        Add-Violation $m
    }
}

# ---------------------------------------------------------------------
# 4. Manifest: required suites present, and not gutted
# ---------------------------------------------------------------------

$manifestReport = New-Object System.Collections.Generic.List[object]

if ($Manifest) {
    if (-not (Test-Path -LiteralPath $Manifest)) {
        Add-Violation ('Expected-suite manifest not found: ' + $Manifest)
    }
    else {
        $doc = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
        $observedSuites = @{}
        foreach ($s in $junit.suites) { $observedSuites[[string]$s.suite] = $s }

        foreach ($entry in @($doc.suites)) {
            $suiteName = [string]$entry.suite
            $moduleName = [string]$entry.module
            # A @Nested suite is reported under its @DisplayName, so the
            # manifest records both and either one satisfies "present".
            $reportName = $suiteName
            if (($entry.PSObject.Properties.Name -contains 'reportName') -and ([string]$entry.reportName)) {
                $reportName = [string]$entry.reportName
            }
            $minTests = 1
            if ($entry.PSObject.Properties.Name -contains 'minTests' -and $null -ne $entry.minTests) {
                $minTests = [int]$entry.minTests
            }
            $inScope = (-not $ModuleScope) -or ($ModuleScope -contains $moduleName)
            # A "containerOf" entry marks a class whose tests are ALL inside @Nested classes.
            # It holds no assertions of its own, so there is nothing for a floor to protect --
            # and JUnit does not reliably emit a report for it. ExportJobLifecycleIntegrationTest
            # produced its own XML when run alone and NOT when the whole module ran, so demanding
            # the report made a green build fail for a non-event. It is also the only thing that
            # turns this entry into a liability: the alternative is to delete it, and deleting
            # manifest entries is how this gate gets quietly defeated.
            #
            # It is NOT a waiver. The protection moves rather than disappears: the nested suites
            # are registered individually with real floors, and check 3b below asserts that every
            # nested suite of a declared container carries one. Deleting or renaming the container
            # makes those nested entries fail as REQUIRED SUITE MISSING.
            $isContainer = ($entry.PSObject.Properties.Name -contains 'containerOf') -and ([string]$entry.containerOf)
            $present = $observedSuites.ContainsKey($reportName)
            $tests = 0
            # Reports are keyed by their REPORTED name (see nestedSuiteNote: a @Nested suite is
            # reported under its @DisplayName), so the count must be read with the same key used for
            # presence. Reading it with the class name silently yields 0 for every nested suite and
            # reports "present but shrank to 0" — a gate that fails for a reason that does not exist.
            if ($present) { $tests = [int]$observedSuites[$reportName].tests }

            $row = [ordered]@{
                suite    = $suiteName
                reportName = $reportName
                module   = $moduleName
                stage    = [string]$entry.stage
                inScope  = $inScope
                present  = $present
                container = $isContainer
                tests    = $tests
                minTests = $minTests
                note     = [string]$entry.note
            }
            $manifestReport.Add($row)

            if (-not $inScope) { continue }

            # A declared container is registered so its report (when JUnit happens to emit one) is
            # not reported as an unknown suite, but its absence is not a failure: it asserts nothing.
            if ($isContainer) {
                if ($minTests -ne 0) {
                    Add-Violation (("DECLARED CONTAINER WITH A FLOOR: '{0}' is marked containerOf and must declare " -f $suiteName) +
                        'minTests 0. A container holds no assertions, so a floor on it is either always satisfied ' +
                        'or a number that will flap; put the floor on the nested suites instead.')
                }
                continue
            }

            if (-not $present) {
                $m = ("REQUIRED SUITE MISSING: {0} (module {1}, stage '{2}'). " -f $suiteName, $moduleName, [string]$entry.stage) +
                     'The module was in scope, so this suite must have produced a report. ' +
                     'A suite that silently stops running is indistinguishable from a suite ' +
                     'that was deleted to make a build pass.'
                Add-Violation $m
            }
            elseif ($tests -lt $minTests) {
                $m = ("REQUIRED SUITE SHRUNK: {0} ran {1} test(s), manifest floor is {2}. " -f $suiteName, $tests, $minTests) +
                     'Either behaviour was removed or tests were skipped out; both need a ' +
                     'deliberate decision recorded in the manifest, not a smaller number.'
                Add-Violation $m
            }
        }

        # ------------------------------------------------------------------
        # 3b. Unregistered suites are a hole in this gate.
        #
        # <p>The loop above only walks the manifest, so a suite that was never registered is invisible:
        # it can be added, skipped, emptied, or deleted and the verdict stays PASS. That is exactly how
        # 231 Phase-6 tests went unregistered while the gate reported "0 missing suites". Anything that
        # produced a report has to be accounted for by a deliberate manifest entry.
        # ------------------------------------------------------------------
        $registeredNames = New-Object 'System.Collections.Generic.HashSet[string]'
        # Entries that are markers rather than suites: their report may or may not appear.
        $declaredContainers = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($entry in @($doc.suites)) {
            [void]$registeredNames.Add([string]$entry.suite)
            if (($entry.PSObject.Properties.Name -contains 'reportName') -and ([string]$entry.reportName)) {
                [void]$registeredNames.Add([string]$entry.reportName)
            }
            if (($entry.PSObject.Properties.Name -contains 'containerOf') -and ([string]$entry.containerOf)) {
                [void]$declaredContainers.Add([string]$entry.suite)
            }
        }
        foreach ($s in $junit.suites) {
            $reported = [string]$s.suite
            # A declared container that JUnit chose to report is accounted for by its marker entry.
            if ($declaredContainers.Contains($reported)) { continue }
            if (-not $registeredNames.Contains($reported)) {
                $m = ("UNREGISTERED SUITE: '{0}' produced a report but is not in the manifest. " -f $reported) +
                      'A suite nobody registered is a suite nobody reviewed, and this gate cannot see ' +
                      'whether it skips, shrinks, or disappears. Add it to ' +
                      'tests/contracts/expected-suites.json with a deliberate minTests floor, or delete the suite.'
                Add-Violation $m
            }
        }

        # ------------------------------------------------------------------
        # 3c. containerOf must not become an escape hatch.
        #
        # A container entry excuses its own absence. The only thing making that safe is that its
        # nested suites carry real floors, so this asserts exactly that: every manifest entry naming
        # a suite inside a declared container must have minTests > 0. Otherwise "it is only a
        # container" could be used to drop a whole subtree of coverage without failing anything.
        #
        # This walks the MANIFEST, not the reports, because a @Nested suite is reported under its
        # display name (see nestedSuiteNote) so the two cannot be matched by string. Deleting a
        # nested entry outright is still caught above, as an UNREGISTERED SUITE, because its report
        # appears under a name nothing else claims.
        foreach ($container in $declaredContainers) {
            foreach ($entry in @($doc.suites)) {
                $name = [string]$entry.suite
                if (-not $name.StartsWith($container + '$')) { continue }
                $floor = 0
                if ($entry.PSObject.Properties.Name -contains 'minTests') { $floor = [int]$entry.minTests }
                if ($floor -lt 1) {
                    Add-Violation ((("UNFLOORED NESTED SUITE: '{0}' lives inside declared container '{1}' but its " -f $name, $container) +
                        'manifest entry has no positive minTests floor. A container entry excuses only itself; ' +
                        'the nested suites are where the assertions are and must be floored.'))
                }
            }
        }
    }
}

# ---------------------------------------------------------------------
# 5. Verdict
# ---------------------------------------------------------------------

$verdict = if ($violations.Count -eq 0) { 'PASS' } else { 'FAIL' }

$result = [ordered]@{
    stage           = $Stage
    verdict         = $verdict
    checkedAtUtc    = $nowUtc.ToString('o')
    resultsDir      = ConvertTo-QArray -Value $ResultsDir
    moduleScope     = ConvertTo-QArray -Value $ModuleScope
    manifest        = $Manifest
    allowList       = $AllowList
    totals          = $t
    suites          = ConvertTo-QArray -Value $junit.suites
    manifestEntries = ConvertTo-QArray -Value $manifestReport
    waiverNotes     = ConvertTo-QArray -Value $notes
    violations      = ConvertTo-QArray -Value $violations
    # A waived skip is still a skip. It is recorded here so the fact
    # survives into the evidence bundle instead of being flattened away by a
    # PASS verdict.
    skippedTestCount = $skippedObserved.Count
    waivedSkipCount  = $waivedCount
}

if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 10 | Out-Null }

Write-QLog ''
foreach ($n in $notes) { Write-QLog $n -Level WARN }

if ($violations.Count -gt 0) {
    Write-QLog "[$Stage] $($violations.Count) violation(s):" -Level ERROR
    foreach ($v in $violations) { Write-QLog ('  - ' + $v) -Level ERROR }
    Write-QLog "[$Stage] VERDICT: FAIL" -Level ERROR
    exit 1
}

Write-QLog "[$Stage] VERDICT: PASS -- $($t.tests) tests across $($t.files) suites, 0 unwaived skips, 0 missing suites." -Level STEP
exit 0
