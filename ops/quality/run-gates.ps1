#!/usr/bin/env pwsh
# =====================================================================
# run-gates.ps1
#
# THE single quality-gate runner. A developer and CI call exactly this.
# Nothing in .github/workflows re-implements a gate, so the pipeline and a
# laptop produce the same verdict from the same commands.
#
#   .\ops\quality\run-gates.ps1                        # every stage, in order
#   .\ops\quality\run-gates.ps1 -ListStages            # what exists
#   .\ops\quality\run-gates.ps1 -Stages format-static,architecture
#   .\ops\quality\run-gates.ps1 -Presets non-gradle     # everything except Gradle
#   .\ops\quality\run-gates.ps1 -EvidenceRoot C:\...\evidence
#
# EVIDENCE
#   <EvidenceRoot>/<UTC timestamp>/
#     evidence.json          machine-readable: every stage, every command,
#                            exit code, duration, test counts, verdict
#     evidence.md            the same, readable by a human at 3am
#     latest.txt             the path of the newest bundle
#     stages/<name>/         per-stage logs and any JSON the stage emitted
#
# THE VERDICT
#   PASS only when every requested stage ran and every one of them passed.
#   A stage whose subject module does not exist yet is a FAILURE. The only
#   way to make it pass is an unexpired entry in
#   ops/quality/waivers/pending-stages.allow.json naming the stage, and
#   -AllowPendingStages. The flag alone does nothing.
#
#   Any stage that reports a skipped test fails the run through the
#   no-hidden-skips stage, and that stage is not optional.
#
# Exit codes: 0 = PASS, 1 = at least one stage failed, 2 = bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    # Comma-separated stage names, in any order; the catalogue order wins.
    [string[]] $Stages,

    # Named bundles of stages. See $Presets below.
    [string[]] $Presets,

    [string] $EvidenceRoot,

    # Print the catalogue and exit.
    [switch] $ListStages,

    # Stop at the first failing stage instead of running the rest and
    # reporting all of them. Off by default: a pipeline that stops at the
    # first red stage hides the red stages behind it.
    [switch] $FailFast,

    # Permit a stage whose module does not exist to pass, but ONLY where an
    # unexpired waiver in ops/quality/waivers/pending-stages.allow.json
    # names it. Without the waiver this switch does nothing.
    [switch] $AllowPendingStages,

    # Validate the catalogue and resolve every command without executing
    # anything. Used by CI's pre-flight and by a developer checking a change
    # to the catalogue.
    [switch] $DryRun,

    [string] $JsonOut
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\quality-common.ps1')

$repoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot

# The database this pipeline must never write to. It is named here, not
# only in the stage catalogue, so a stage that tries to mutate it can be
# caught by ops/quality/checks/check-disposable-targets.ps1 and so a human
# reading the runner sees the boundary without having to open the JSON.
$protectedDatabase = 'firstelement'

$cataloguePath = Join-Path $PSScriptRoot 'gate-stages.json'
if (-not (Test-Path -LiteralPath $cataloguePath)) {
    Write-QLog ("gate catalogue not found: {0}" -f $cataloguePath) -Level ERROR
    exit 2
}
$catalogue = Get-Content -Raw -LiteralPath $cataloguePath | ConvertFrom-Json

if (-not $EvidenceRoot) { $EvidenceRoot = Join-Path $repoRoot 'build\quality-evidence' }
$EvidenceRoot = New-QDirectory -Path $EvidenceRoot

# --- presets ---------------------------------------------------------
$Presets = if ($Presets.Count -gt 0) { $Presets } else { @() }
$presetMap = [ordered]@{
    'non-gradle' = @('format-static', 'architecture', 'openapi-contract', 'no-hidden-skips')
    'static'     = @('format-static', 'legacy-node-static', 'architecture', 'openapi-contract')
    'backend'    = @('backend-compile', 'backend-unit', 'backend-integration', 'no-hidden-skips')
    'data'       = @('migration-verification', 'restore-rehearsal', 'no-hidden-skips')
    'full'       = @()
}

if ($ListStages) {
    Write-QLog ('catalogue: ' + $cataloguePath) -Level STEP
    Write-QLog ('repository: ' + $repoRoot) -Level STEP
    Write-QLog ''
    Write-QLog ('{0,-32} {1,-9} {2}' -f 'STAGE', 'KIND', 'TITLE') -Level STEP
    $i = 1
    foreach ($s in @($catalogue.stages)) {
        $kind = 'command'
        if ($s.PSObject.Properties.Name -contains 'requiresPaths' -and @($s.requiresPaths).Count -gt 0) { $kind = 'staged' }
        $pending = ''
        if ($kind -eq 'staged') {
            foreach ($rp in @($s.requiresPaths)) {
                if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $rp))) { $pending = ' [MODULE MISSING]' }
            }
        }
        Write-QLog ('{0,2}. {1,-30} {2,-9} {3}{4}' -f $i, $s.name, $kind, $s.title, $pending) -Level $(if ($pending) { 'WARN' } else { 'INFO' })
        Write-QLog ('      owner   : {0}' -f $s.owner) -Level INFO
        Write-QLog ('      requires: {0}' -f $s.requirement) -Level INFO
        if ($s.PSObject.Properties.Name -contains 'rationale') { Write-QLog ('      why     : {0}' -f $s.rationale) -Level INFO }
        if ($s.PSObject.Properties.Name -contains 'whyRerunTasks') { Write-QLog ('      gradle  : {0}' -f $s.whyRerunTasks) -Level INFO }
        if ($s.PSObject.Properties.Name -contains 'note') { Write-QLog ('      note    : {0}' -f $s.note) -Level INFO }
        Write-QLog ''
        $i++
    }
    Write-QLog 'presets: ' + (($presetMap.Keys) -join ', ') -Level STEP
    Write-QLog ('  full       = every stage ({0} total)' -f @($catalogue.stages).Count) -Level INFO
    foreach ($k in $presetMap.Keys) {
        if ($k -eq 'full') { continue }
        Write-QLog ('  {0,-11} = {1}' -f $k, (($presetMap[$k]) -join ', ')) -Level INFO
    }
    exit 0
}

# --- resolve the requested set ---------------------------------------
# `powershell -File run-gates.ps1 -Stages a,b,c` hands the runner one
# string, not an array, so the values are split here rather than relying
# on the caller's shell to have done it.
$splitValues = {
    param([string[]] $Values)
    $out = New-Object System.Collections.Generic.List[string]
    if (-not $Values) { return $out }
    foreach ($v in $Values) {
        foreach ($piece in ([string]$v -split ';')) {
            foreach ($p2 in ($piece -split ',')) {
                $t = $p2.Trim()
                if ($t) { $out.Add($t) }
            }
        }
    }
    return $out
}
$Stages = @(& $splitValues -Values $Stages)
$Presets = @(& $splitValues -Values $Presets)

$all = @($catalogue.stages)
$byName = @{}
foreach ($s in $all) { $byName[[string]$s.name] = $s }

$requested = New-Object System.Collections.Generic.List[string]
foreach ($p in $Presets) {
    $key = ([string]$p).ToLowerInvariant()
    if (-not $presetMap.Contains($key)) {
        Write-QLog ("unknown preset '{0}'. Known: {1}" -f $p, (($presetMap.Keys) -join ', ')) -Level ERROR
        exit 2
    }
    foreach ($n in @($presetMap[$key])) { $requested.Add([string]$n) }
}
foreach ($n in @($Stages)) {
    if (-not $byName.ContainsKey([string]$n)) {
        Write-QLog ("unknown stage '{0}'. Run -ListStages." -f $n) -Level ERROR
        exit 2
    }
    $requested.Add([string]$n)
}
if ($requested.Count -eq 0) { foreach ($s in $all) { $requested.Add([string]$s.name) } }

$selected = New-Object System.Collections.Generic.List[object]
$seen = @{}
foreach ($n in $requested) {
    if ($seen.ContainsKey($n)) { continue }
    $seen[$n] = $true
    $selected.Add($byName[$n])
}

# --- tool resolution --------------------------------------------------
$ps = Resolve-QPowerShell
$gradlew = $null
if (Test-Path -LiteralPath (Join-Path $repoRoot 'gradlew.bat')) { $gradlew = Join-Path $repoRoot 'gradlew.bat' }
elseif (Test-Path -LiteralPath (Join-Path $repoRoot 'gradlew')) { $gradlew = Join-Path $repoRoot 'gradlew' }
$yarn = Resolve-QCommandPath -Name 'yarn'
# npm alongside yarn: `frontend/` is a standalone npm package, not a yarn workspace,
# so a stage that needs Playwright or the Angular CLI has to go through npm with
# --prefix. Naming {yarn} there resolved nothing and failed with exit 127.
$npm = Resolve-QCommandPath -Name 'npm'
$docker = Resolve-QCommandPath -Name 'docker'
# node is needed by the shadow harness and by the PN-08 verdict proof. Resolving it
# here rather than inside a stage means a stage that names {node} fails at start-up
# with "node was not found" instead of half-way through a five-minute run.
$node = Resolve-QCommandPath -Name 'node'

function Resolve-Token {
    param([string] $Value, [string] $StageDir, [string] $RunRoot)
    if ($null -eq $Value) { return $null }
    $v = [string]$Value
    $v = $v.Replace('{repoRoot}', $repoRoot)
    $v = $v.Replace('{evidenceRoot}', $RunRoot)
    $v = $v.Replace('{stageDir}', $StageDir)
    $v = $v.Replace('{powershell}', $ps.Exe)
    $v = $v.Replace('{gradlew}', [string]$gradlew)
    $v = $v.Replace('{yarn}', [string]$yarn)
    $v = $v.Replace('{npm}', [string]$npm)
    $v = $v.Replace('{docker}', [string]$docker)
    $v = $v.Replace('{node}', [string]$node)
    return $v
}

# --- pending-stage waiver --------------------------------------------
$pendingAllowPath = Join-Path $PSScriptRoot 'waivers\pending-stages.allow.json'
$pendingAllow = @()
if (Test-Path -LiteralPath $pendingAllowPath) {
    $pa = Get-Content -Raw -LiteralPath $pendingAllowPath | ConvertFrom-Json
    if ($pa.PSObject.Properties.Name -contains 'entries' -and $null -ne $pa.entries) { $pendingAllow = @($pa.entries) }
}
$nowUtc = (Get-Date).ToUniversalTime()

# --- evidence root ----------------------------------------------------
$runId = Get-QUtcTimestamp
$runRoot = New-QDirectory (Join-Path $EvidenceRoot $runId)
Write-QLog ("evidence bundle: {0}" -f $runRoot) -Level STEP
Write-QLog ("powerShell    : {0} {1}" -f $ps.Exe, $ps.Version) -Level INFO
Write-QLog ("gradle wrapper: {0}" -f $(if ($gradlew) { $gradlew } else { 'NOT FOUND' })) -Level $(if ($gradlew) { 'INFO' } else { 'WARN' })
Write-QLog ("yarn          : {0}" -f $(if ($yarn) { $yarn } else { 'NOT FOUND' })) -Level $(if ($yarn) { 'INFO' } else { 'WARN' })
Write-QLog ("npm           : {0}" -f $(if ($npm) { $npm } else { 'NOT FOUND' })) -Level $(if ($npm) { 'INFO' } else { 'WARN' })
Write-QLog ("docker        : {0}" -f $(if ($docker) { $docker } else { 'NOT FOUND' })) -Level $(if ($docker) { 'INFO' } else { 'WARN' })
Write-QLog ("stages        : {0} of {1}" -f $selected.Count, $all.Count) -Level STEP
Write-QLog ''

if ($DryRun) {
    Write-QLog 'DRY RUN: catalogue and command resolution only, nothing executed.' -Level STEP
    $preview = New-Object System.Collections.Generic.List[object]
    foreach ($s in $selected) {
        $stageDir = Join-Path (Join-Path $runRoot 'stages') ([string]$s.name)
        $cmds = New-Object System.Collections.Generic.List[object]
        foreach ($c in @($s.commands)) {
            $exe = Resolve-Token -Value ([string]$c.exe) -StageDir $stageDir -RunRoot $runRoot
            $argList = New-Object System.Collections.Generic.List[string]
            foreach ($a in @($c.args)) { $argList.Add((Resolve-Token -Value ([string]$a) -StageDir $stageDir -RunRoot $runRoot)) }
            $exePresent = ($null -ne $exe) -and ($exe -ne '') -and (($exe -match '\\') -or ($exe -match '/') -or ($null -ne (Get-Command $exe -ErrorAction SilentlyContinue)))
            $cmds.Add([ordered]@{
                label = [string]$c.label
                command = ((@($exe) + @($argList)) -join ' ')
                executableResolves = $exePresent
            })
        }
        $preview.Add([ordered]@{ stage = [string]$s.name; title = [string]$s.title; commands = ConvertTo-QArray -Value $cmds })
    }
    $dry = [ordered]@{
        verdict = 'DRY_RUN'; runRoot = $runRoot; stages = ConvertTo-QArray -Value $preview
        unresolvedExecutables = @($preview | ForEach-Object { $st = $_; foreach ($c in $st.commands) { if (-not $c.executableResolves) { ($st.stage + ' -> ' + $c.command) } } })
    }
    if ($JsonOut) { Write-QJson -Path $JsonOut -Object $dry -Depth 8 | Out-Null }
    foreach ($p in $dry.unresolvedExecutables) { Write-QLog ('  UNRESOLVED: ' + $p) -Level ERROR }
    Write-QLog ('dry run complete; evidence: {0}' -f $runRoot) -Level STEP
    exit 0
}

# --- execute ----------------------------------------------------------
$stageRecords = New-Object System.Collections.Generic.List[object]
$overallFail = $false

foreach ($s in $selected) {
    $name = [string]$s.name
    $title = [string]$s.title
    $stageDir = New-QDirectory (Join-Path (Join-Path $runRoot 'stages') $name)

    Write-QLog ("=== STAGE {0} :: {1}" -f $name, $title) -Level STEP

    $record = [ordered]@{
        name          = $name
        title         = $title
        owner         = [string]$s.owner
        requirement   = [string]$s.requirement
        rationale     = [string]$s.rationale
        status        = 'PENDING'
        verdict       = 'FAIL'
        startedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
        durationMs    = 0
        commands      = @()
        alwaysCommands = @()
        evidenceDir   = $stageDir
        tests         = $null
        findings      = @()
        notes         = @()
    }

    # pending-module gate
    $missingPaths = New-Object System.Collections.Generic.List[string]
    if ($s.PSObject.Properties.Name -contains 'requiresPaths') {
        foreach ($rp in @($s.requiresPaths)) {
            if (-not (Test-Path -LiteralPath (Join-Path $repoRoot ([string]$rp)))) { $missingPaths.Add([string]$rp) }
        }
    }
    if ($missingPaths.Count -gt 0) {
        $msg = ("REQUIRED MODULE ABSENT: {0} -- {1}. Stage '{2}' cannot pass because its subject does not exist. " -f ($missingPaths -join ', '), [string]$s.title, $name) +
               'A gate for a module that has not been built yet must be RED, not green. To stage it deliberately, add an entry to ' +
               'ops/quality/waivers/pending-stages.allow.json naming the stage, the phase that will create the module, a ticket and an expiry date, and re-run with -AllowPendingStages.'
        $waiver = $null
        if ($AllowPendingStages) {
            foreach ($e in $pendingAllow) {
                if ([string]$e.stage -ne $name) { continue }
                $exp = $null
                $expOk = $true
                try { $exp = ConvertTo-QUtcDate -Value ([string]$e.expiresOn) } catch { $expOk = $false }
                if (-not $expOk) { $record.findings += ('waiver for stage {0} has an unreadable expiresOn' -f $name); continue }
                if ($exp -le $nowUtc) { $record.findings += ('waiver for stage {0} EXPIRED on {1}' -f $name, $exp.ToString('yyyy-MM-dd')); continue }
                if (-not $e.ticket) { $record.findings += ('waiver for stage {0} has no ticket' -f $name); continue }
                $waiver = $e
                break
            }
        }
        if ($waiver) {
            $record.status = 'PENDING_WAIVED'
            $record.verdict = 'PASS'
            $record.notes += ('module absent, staged by waiver: {0} (ticket {1}, expires {2})' -f [string]$waiver.reason, [string]$waiver.ticket, [string]$waiver.expiresOn)
            Write-QLog ("  PENDING (waived): module absent; ticket {0}, expires {1}" -f [string]$waiver.ticket, [string]$waiver.expiresOn) -Level WARN
        }
        else {
            $record.status = 'MODULE_ABSENT'
            $record.verdict = 'FAIL'
            $record.findings += $msg
            Write-QLog ('  FAIL: ' + $msg) -Level ERROR
            $overallFail = $true
            $stageRecords.Add($record)
            if ($FailFast) { break }
            continue
        }
    }

    $stageFail = $false
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    foreach ($group in @('commands', 'alwaysCommands')) {
        if (-not ($s.PSObject.Properties.Name -contains $group)) { continue }
        $isAlways = ($group -eq 'alwaysCommands')
        foreach ($c in @($s.$group)) {
            if ($stageFail -and -not $isAlways) { break }
            $exe = Resolve-Token -Value ([string]$c.exe) -StageDir $stageDir -RunRoot $runRoot
            $argList = New-Object System.Collections.Generic.List[string]
            foreach ($a in @($c.args)) { $argList.Add((Resolve-Token -Value ([string]$a) -StageDir $stageDir -RunRoot $runRoot)) }

            $logName = '{0}.log' -f (ConvertTo-QSlug -Text ([string]$c.label))
            $logPath = Join-Path $stageDir $logName
            $timeout = 60
            if ($s.PSObject.Properties.Name -contains 'timeoutMinutes') { $timeout = [int]$s.timeoutMinutes }

            $res = Invoke-QProcess -Exe $exe -Arguments (ConvertTo-QArray -Value $argList) `
                -WorkDir $repoRoot -LogPath $logPath -TimeoutMinutes $timeout

            $cmdRecord = [ordered]@{
                label     = [string]$c.label
                command   = $res.command
                exitCode  = $res.exitCode
                durationMs = $res.durationMs
                log       = (Resolve-Path -LiteralPath $logPath -ErrorAction SilentlyContinue).Path
                group     = $group
            }
            if ($isAlways) { $record.alwaysCommands += $cmdRecord } else { $record.commands += $cmdRecord }

            $mark = if ($res.exitCode -eq 0) { 'ok  ' } else { 'FAIL' }
            Write-QLog ('  {0} [{1}] exit {2} ({3} ms) {4}' -f $mark, $group, $res.exitCode, $res.durationMs, [string]$c.label) `
                -Level $(if ($res.exitCode -eq 0) { 'INFO' } else { 'ERROR' })

            if ($res.exitCode -ne 0 -and -not $isAlways) {
                $stageFail = $true
                $record.findings += ('command failed: {0} (exit {1}) -- log {2}' -f $res.command, $res.exitCode, $logPath)
            }
        }
    }

    $sw.Stop()
    $record.durationMs = [int]$sw.ElapsedMilliseconds
    $record.finishedAtUtc = (Get-Date).ToUniversalTime().ToString('o')

    # JUnit attribution for this stage
    if (($s.PSObject.Properties.Name -contains 'junit') -and -not $DryRun) {
        try {
            $j = Get-QJUnitResults -Directories @($s.junit.resultsDirs | ForEach-Object { Join-Path $repoRoot $_ })
            $record.tests = [ordered]@{
                suites = $j.totals.files
                tests  = $j.totals.tests
                failures = $j.totals.failures
                errors = $j.totals.errors
                skippedAttribute = $j.totals.skippedAttribute
                skippedElements  = $j.totals.skippedElements
                scope = ConvertTo-QArray -Value $s.junit.scope
            }
            Write-QLog ('  tests: {0} suites, {1} tests, {2} failures, {3} errors, {4} skipped' -f `
                    $j.totals.files, $j.totals.tests, $j.totals.failures, $j.totals.errors, $j.totals.skippedElements) -Level INFO
        }
        catch {
            $record.tests = [ordered]@{ error = $_.Exception.Message }
        }
    }

    if ($stageFail) { $record.status = 'FAILED'; $record.verdict = 'FAIL'; $overallFail = $true }
    else { $record.status = 'PASSED'; $record.verdict = 'PASS' }

    $stageRecords.Add($record)
    if ($stageFail -and $FailFast) { break }
}

# --- aggregate -------------------------------------------------------
$totalTests = 0
$totalSkipped = 0
$totalSuites = 0
foreach ($r in $stageRecords) {
    if ($null -ne $r.tests) {
        if ($r.tests.PSObject.Properties.Name -contains 'tests' -and $null -ne $r.tests.tests) {
            $totalTests += [int]$r.tests.tests
            $totalSkipped += [int]$r.tests.skippedElements
            $totalSuites += [int]$r.tests.suites
        }
    }
}

$verdict = if ($overallFail) { 'FAIL' } else { 'PASS' }

$evidence = [ordered]@{
    schemaVersion = 1
    verdict       = $verdict
    runId         = $runId
    startedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
    finishedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    repository    = $repoRoot
    branch        = $(if (Get-Command git -ErrorAction SilentlyContinue) { (git -C $repoRoot rev-parse --abbrev-ref HEAD 2>$null) } else { $null })
    host          = [ordered]@{
        os            = [System.Environment]::OSVersion.VersionString
        powerShell    = $ps.Version
        dotNet        = [System.Environment]::Version.ToString()
        gradleWrapper = $gradlew
        yarn          = $yarn
        npm           = $npm
        npmVersion    = $(if ($npm) { (& $npm --version 2>$null) } else { $null })
        docker        = $(if ($docker) { (& $docker version --format '{{.Server.Version}}' 2>$null) } else { $null })
        node          = $(if (Get-Command node -ErrorAction SilentlyContinue) { (node --version) } else { $null })
    }
    allowPendingStages = [bool]$AllowPendingStages
    totals        = [ordered]@{
        stages          = $stageRecords.Count
        stagesPassed    = @($stageRecords | Where-Object { $_.verdict -eq 'PASS' }).Count
        stagesFailed    = @($stageRecords | Where-Object { $_.verdict -ne 'PASS' }).Count
        suites          = $totalSuites
        tests           = $totalTests
        skipped         = $totalSkipped
    }
    stages        = ConvertTo-QArray -Value $stageRecords
}

$evidenceJson = Join-Path $runRoot 'evidence.json'
Write-QJson -Path $evidenceJson -Object $evidence -Depth 12 | Out-Null
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $evidence -Depth 12 | Out-Null }

# markdown
$md = New-Object System.Collections.Generic.List[string]
$null = $md.Add('# FirstElement quality-gate evidence')
$null = $md.Add('')
$null = $md.Add(('- **Verdict:** `{0}`' -f $verdict))
$null = $md.Add(('- **Run:** `{0}`' -f $runId))
$null = $md.Add(('- **Branch:** `{0}`' -f $evidence.branch))
$null = $md.Add(('- **PowerShell:** `{0}`  **Docker:** `{1}`  **Node:** `{2}`' -f $ps.Version, $evidence.host.docker, $evidence.host.node))
$null = $md.Add(('- **Stages:** {0} run, {1} passed, {2} failed' -f $evidence.totals.stages, $evidence.totals.stagesPassed, $evidence.totals.stagesFailed))
$null = $md.Add(('- **Tests attributed to these stages:** {0} tests in {1} suites, {2} skipped' -f $totalTests, $totalSuites, $totalSkipped))
$null = $md.Add('')
$null = $md.Add('## Stages')
$null = $md.Add('')
$null = $md.Add('| # | Stage | Status | Duration | Tests | Skipped | Owner |')
$null = $md.Add('|---|---|---|---|---|---|---|')
$i = 1
foreach ($r in $stageRecords) {
    $t = '-'
    $sk = '-'
    if ($null -ne $r.tests) {
        if ($r.tests.PSObject.Properties.Name -contains 'tests' -and $null -ne $r.tests.tests) {
            $t = [string]$r.tests.tests
            $sk = [string]$r.tests.skippedElements
        }
    }
    $null = $md.Add(('| {0} | `{1}` | **{2}** | {3} ms | {4} | {5} | {6} |' -f $i, $r.name, $r.status, $r.durationMs, $t, $sk, $r.owner))
    $i++
}
$null = $md.Add('')
$null = $md.Add('## Commands')
$null = $md.Add('')
foreach ($r in $stageRecords) {
    $null = $md.Add(('### `{0}` -- {1} ({2})' -f $r.name, $r.title, $r.status))
    $null = $md.Add('')
    if ($r.rationale) { $null = $md.Add(('> {0}' -f $r.rationale)); $null = $md.Add('') }
    foreach ($c in @($r.commands)) {
        $null = $md.Add(('- `{0}` -- exit `{1}`, {2} ms' -f $c.label, $c.exitCode, $c.durationMs))
        $null = $md.Add(('  ```'))
        $null = $md.Add(('  {0}' -f $c.command))
        $null = $md.Add(('  ```'))
    }
    foreach ($f in @($r.findings)) { $null = $md.Add(('- FINDING: {0}' -f $f)) }
    foreach ($n in @($r.notes)) { $null = $md.Add(('- NOTE: {0}' -f $n)) }
    $null = $md.Add('')
}
$null = $md.Add('## Reproduction')
$null = $md.Add('')
$null = $md.Add('```powershell')
$null = $md.Add('.\ops\quality\run-gates.ps1 -Stages ' + (($stageRecords | ForEach-Object { $_.name }) -join ','))
$null = $md.Add('```')
$evidenceMd = Join-Path $runRoot 'evidence.md'
$md | Out-File -FilePath $evidenceMd -Encoding utf8

$runRoot | Out-File -FilePath (Join-Path $EvidenceRoot 'latest.txt') -Encoding ascii -Force

Write-QLog ''
Write-QLog ('VERDICT {0}: {1} stage(s), {2} passed, {3} failed, {4} tests attributed, {5} skipped' -f `
        $verdict, $evidence.totals.stages, $evidence.totals.stagesPassed, $evidence.totals.stagesFailed, $totalTests, $totalSkipped) `
    -Level $(if ($verdict -eq 'PASS') { 'STEP' } else { 'ERROR' })
Write-QLog ("evidence: {0}" -f $evidenceJson) -Level INFO
Write-QLog ("markdown : {0}" -f $evidenceMd) -Level INFO

if ($verdict -eq 'PASS') { exit 0 }
exit 1