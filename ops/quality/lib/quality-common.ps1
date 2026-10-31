#!/usr/bin/env pwsh
# =====================================================================
# quality-common.ps1
#
# Shared helpers for the FirstElement quality gates. Dot-sourced by
# ops/quality/run-gates.ps1 and by every script under ops/quality/checks.
#
# COMPATIBILITY: Windows PowerShell 5.1 and PowerShell 7+. Nothing here
# uses PS7-only syntax (`??`, ternary, -AsHashtable, Join-Path with more
# than two components, ForEach-Object -Parallel, Test-Json). The
# repository has no `pwsh` on the developer path today, so 5.1 is the
# floor and everything is written to that floor.
#
# `Set-StrictMode` is deliberately NOT enabled: the JUnit and evidence
# objects are PSCustomObject and reading an absent property under
# strict mode is a terminating error that would mask the real finding.
# =====================================================================

# ---------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------

function Get-RepositoryRoot {
    <#
      .SYNOPSIS
      Walk up from this file until settings.gradle.kts is found.
      lib/ -> quality/ -> ops/ -> repository root.
    #>
    param([string] $StartFrom)
    if (-not $StartFrom) { $StartFrom = $PSScriptRoot }
    $dir = (Resolve-Path -LiteralPath $StartFrom).Path
    for ($i = 0; $i -lt 12; $i++) {
        if (Test-Path (Join-Path $dir 'settings.gradle.kts')) { return $dir }
        $parent = Split-Path -Parent $dir
        if (-not $parent -or $parent -eq $dir) { break }
        $dir = $parent
    }
    throw "Could not locate the repository root (no settings.gradle.kts above '$StartFrom')."
}

function New-QDirectory {
    param([Parameter(Mandatory = $true)][string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Force -Path $Path | Out-Null
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Write-QJson {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)] $Object,
        [int] $Depth = 8
    )
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    ($Object | ConvertTo-Json -Depth $Depth) | Out-File -FilePath $Path -Encoding utf8
    return $Path
}

# ---------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------

$script:QLogPrefix = 'firstelement-quality'

function Write-QLog {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string] $Message,
        [ValidateSet('INFO', 'WARN', 'ERROR', 'STEP')][string] $Level = 'INFO'
    )
    $ts = (Get-Date).ToUniversalTime().ToString('HH:mm:ss')
    $tag = switch ($Level) {
        'WARN'  { ' WARN ' }
        'ERROR' { 'ERROR ' }
        'STEP'  { ' STAGE' }
        default { ' INFO ' }
    }
    $host.UI.WriteLine(('{0} {1} {2}' -f $ts, $tag, $Message))
}

# ---------------------------------------------------------------------
# Tool resolution
# ---------------------------------------------------------------------

function Resolve-QPowerShell {
    <#
      .SYNOPSIS
      The PowerShell executable the gates shell out to. pwsh when present
      (CI on Linux), Windows PowerShell otherwise. The chosen flavour and
      version are recorded in the evidence bundle so a result can be
      reproduced.
    #>
    $pwsh = Get-Command 'pwsh' -ErrorAction SilentlyContinue
    if ($pwsh) {
        return [ordered]@{ Exe = $pwsh.Source; Version = (& $pwsh.Source -NoProfile -Command '$PSVersionTable.PSVersion.ToString()') }
    }
    $win = Get-Command 'powershell.exe' -ErrorAction SilentlyContinue
    if (-not $win) { throw 'Neither pwsh nor powershell.exe is on PATH.' }
    return [ordered]@{ Exe = $win.Source; Version = $PSVersionTable.PSVersion.ToString() }
}

function Resolve-QCommandPath {
    <#
      .SYNOPSIS
      Resolve a launcher name to an absolute path, trying the Windows
      shim forms first. Returns $null when the tool is absent so the
      caller can FAIL with a precise message instead of a shell error.
    #>
    param([Parameter(Mandatory = $true)][string] $Name)
    $candidates = @($Name)
    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        $candidates = @("$Name.cmd", "$Name.exe", "$Name.bat", $Name)
    }
    foreach ($c in $candidates) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

# ---------------------------------------------------------------------
# Process invocation
# ---------------------------------------------------------------------

function Invoke-QProcess {
    <#
      .SYNOPSIS
      Run one child process, capture merged stdout/stderr to a log file,
      and return a record with the real exit code and duration.

      .DESCRIPTION
      A gate that cannot report an exit code is not evidence. This never
      throws on a non-zero exit: the caller decides whether that is a
      stage failure, so a stage can run its cleanup commands regardless.
    #>
    param(
        [Parameter(Mandatory = $true)][string]   $Exe,
        [string[]] $Arguments = @(),
        [string]   $WorkDir,
        [string]   $LogPath,
        [int]      $TimeoutMinutes = 60,
        [hashtable] $Environment = @{}
    )

    if ($LogPath) {
        $logParent = Split-Path -Parent $LogPath
        if ($logParent -and -not (Test-Path -LiteralPath $logParent)) {
            New-Item -ItemType Directory -Force -Path $logParent | Out-Null
        }
    }

    $saved = @{}
    foreach ($k in $Environment.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process') }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add(('# command : {0} {1}' -f $Exe, ($Arguments -join ' ')))
    if ($WorkDir) { $lines.Add(('# cwd     : {0}' -f $WorkDir)) }
    $lines.Add(('# started : {0}' -f (Get-Date).ToUniversalTime().ToString('o')))
    $lines.Add('# ------------------------------------------------------------------')

    $previousEap = $ErrorActionPreference
    $previousLocation = Get-Location
    try {
        $ErrorActionPreference = 'Continue'
        foreach ($k in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($k, [string]$Environment[$k], 'Process')
        }
        if ($WorkDir) { Set-Location -LiteralPath $WorkDir }

        # Start-Process would lose the merged-stream ordering and adds a
        # wrapper process; a direct invocation with 2>&1 is what every
        # phase report in this repository already uses, so the log format
        # stays familiar to whoever reads it.
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $output = & $Exe @Arguments 2>&1
        $exit = $LASTEXITCODE
        $sw.Stop()

        foreach ($o in @($output)) {
            if ($null -eq $o) { continue }
            if ($o -is [System.Management.Automation.ErrorRecord]) {
                $lines.Add($o.ToString())
            } else {
                $lines.Add([string]$o)
            }
        }
        $durationMs = [int]$sw.ElapsedMilliseconds
    }
    catch {
        $lines.Add(('# launch failed: ' + $_.Exception.Message))
        $exit = 127
        $durationMs = 0
    }
    finally {
        if ($WorkDir) { Set-Location -LiteralPath $previousLocation.Path }
        foreach ($k in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process')
        }
        $ErrorActionPreference = $previousEap
    }

    $lines.Add('# ------------------------------------------------------------------')
    $lines.Add(('# exit    : {0}' -f $exit))
    $lines.Add(('# duration: {0} ms' -f $durationMs))
    $lines.Add(('# finished: {0}' -f (Get-Date).ToUniversalTime().ToString('o')))

    if ($LogPath) {
        $lines | Out-File -FilePath $LogPath -Encoding utf8
    }

    return [ordered]@{
        command   = (@($Exe) + @($Arguments)) -join ' '
        exitCode  = [int]$exit
        durationMs = $durationMs
        log       = if ($LogPath) { $LogPath } else { $null }
    }
}

# ---------------------------------------------------------------------
# Duration / percentile helpers (shared by the performance harness)
# ---------------------------------------------------------------------

function Get-QPercentile {
    <#
      .SYNOPSIS
      Nearest-rank percentile over a sorted-or-unsorted list of
      milliseconds. Nearest-rank is used because a p95 interpolated
      between two samples is not a number any request actually took.
    #>
    param(
        [Parameter(Mandatory = $true)][double[]] $Values,
        [Parameter(Mandatory = $true)][double]   $Percentile
    )
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [int][Math]::Ceiling($Percentile / 100.0 * $sorted.Count)
    if ($rank -lt 1) { $rank = 1 }
    if ($rank -gt $sorted.Count) { $rank = $sorted.Count }
    return [double]$sorted[$rank - 1]
}

function Get-QStatistics {
    # PHASE 47. Mandatory was removed here. The body has ALWAYS handled an empty set -- it returns a
    # zeroed, null-percentile result -- but [Parameter(Mandatory = $true)] rejects an empty array
    # during BINDING, before the body runs, so the guard was unreachable for precisely the case it
    # exists to cover. A scenario where every request failed is the moment a report matters most,
    # and that was the moment the harness died instead of reporting why.
    param([double[]] $Values)
    if (-not $Values -or $Values.Count -eq 0) {
        return [ordered]@{ count = 0; p50 = $null; p95 = $null; p99 = $null; min = $null; max = $null; mean = $null }
    }
    $sorted = @($Values | Sort-Object)
    return [ordered]@{
        count = $sorted.Count
        min   = [double]$sorted[0]
        max   = [double]$sorted[$sorted.Count - 1]
        mean  = [Math]::Round((($sorted | Measure-Object -Average).Average), 2)
        p50   = Get-QPercentile -Values $Values -Percentile 50
        p95   = Get-QPercentile -Values $Values -Percentile 95
        p99   = Get-QPercentile -Values $Values -Percentile 99
    }
}

# ---------------------------------------------------------------------
# JUnit XML aggregation
# ---------------------------------------------------------------------

function Get-QJUnitResults {
    <#
      .SYNOPSIS
      Parse every TEST-*.xml under the given directories.

      .DESCRIPTION
      Counts skipped twice over: once from the testsuite/@skipped
      attribute (what Gradle reports) and once from the <skipped/> child
      elements (what actually happened). A report whose attribute says 0
      while a <skipped/> element exists is a reporting defect, and this
      returns both numbers so the gate can fail on the discrepancy rather
      than trusting the attribute.
    #>
    param([Parameter(Mandatory = $true)][string[]] $Directories)

    $files = New-Object System.Collections.Generic.List[string]
    foreach ($d in $Directories) {
        if (-not (Test-Path -LiteralPath $d)) { continue }
        # -Filter combined with -LiteralPath and -Recurse does not reliably
        # filter the leaf name in Windows PowerShell, so it is done by hand.
        # An unreliable filter here means silently zero results, which the
        # checker would then report as "nothing ran" -- technically true,
        # completely misleading.
        $found = Get-ChildItem -LiteralPath $d -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'TEST-*.xml' }
        foreach ($f in $found) { $files.Add($f.FullName) }
    }

    $suites   = New-Object System.Collections.Generic.List[object]
    $totals   = [ordered]@{ files = 0; tests = 0; failures = 0; errors = 0; skippedAttribute = 0; skippedElements = 0 }
    $parseErrors = New-Object System.Collections.Generic.List[string]

    foreach ($file in $files) {
        try {
            [xml] $x = Get-Content -Raw -LiteralPath $file
        }
        catch {
            $parseErrors.Add(('{0}: {1}' -f $file, $_.Exception.Message))
            continue
        }
        $node = $x.testsuite
        if (-not $node) { $parseErrors.Add("$file : no <testsuite> element"); continue }

        $skippedEls = @($node.testcase | Where-Object { $_.skipped })
        $suite = [ordered]@{
            suite          = [string]$node.name
            file           = $file
            module         = (Get-QModuleFromResultPath -Path $file)
            tests          = [int]$node.tests
            failures       = [int]$node.failures
            errors         = [int]$node.errors
            skipped        = [int]$node.skipped
            skippedElements = $skippedEls.Count
            time           = [double]$node.time
            skippedTests   = @($skippedEls | ForEach-Object { [string]$_.name })
        }
        $suites.Add($suite)

        $totals.files           = $totals.files + 1
        $totals.tests           = $totals.tests + $suite.tests
        $totals.failures        = $totals.failures + $suite.failures
        $totals.errors          = $totals.errors + $suite.errors
        $totals.skippedAttribute = $totals.skippedAttribute + $suite.skipped
        $totals.skippedElements = $totals.skippedElements + $suite.skippedElements
    }

    return [ordered]@{
        directories  = ConvertTo-QArray -Value $Directories
        totals       = $totals
        suites       = ConvertTo-QArray -Value $suites
        parseErrors  = ConvertTo-QArray -Value $parseErrors
        resultsEmpty = ($files.Count -eq 0)
    }
}

function Get-QModuleFromResultPath {
    <#
      .SYNOPSIS
      Gradle writes <repo>/backend/<module>/build/test-results/<task>/*.xml.
      The module is the path segment right after `backend`, so a suite can
      be attributed to a module and a manifest entry can declare which
      modules must have produced results.
    #>
    param([Parameter(Mandatory = $true)][string] $Path)
    $m = [regex]::Match($Path, '[\\/]backend[\\/](?<module>[^\\/]+)[\\/]build[\\/]test-results[\\/]')
    if ($m.Success) { return $m.Groups['module'].Value }
    return $null
}

function Get-QUtcTimestamp {
    return (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
}

function ConvertTo-QSlug {
    param([Parameter(Mandatory = $true)][string] $Text)
    return ($Text -replace '[^A-Za-z0-9._-]', '-').ToLowerInvariant()
}

function ConvertTo-QUtcDate {
    <#
      .SYNOPSIS
      Parse a date/time to UTC, treating a bare date as UTC midnight.

      .DESCRIPTION
      `[datetime]::Parse("2020-01-01").ToUniversalTime()` returns
      2019-12-31T22:00Z on a UTC+2 host. A waiver that says "expires
      2020-01-01" then reports an expiry one day earlier than its author
      wrote, and a gate that prints a date an operator has to reconcile by
      hand is a gate people stop reading. AssumeUniversal +
      AdjustToUniversal makes the file's literal the truth.
    #>
    param([Parameter(Mandatory = $true)][string] $Value)
    $styles = [System.Globalization.DateTimeStyles]::AssumeUniversal -bor
              [System.Globalization.DateTimeStyles]::AdjustToUniversal
    return [datetime]::Parse($Value, [System.Globalization.CultureInfo]::InvariantCulture, $styles)
}

function ConvertTo-QArray {
    <#
      .SYNOPSIS
      Normalise any collection into a plain PowerShell array.

      .DESCRIPTION
      Two PowerShell 5.1 traps are avoided here on purpose.

      1. `@($x)` throws "Argument types do not match" when $x is an EMPTY
         generic List. An empty violations list is the normal, good-path
         state of every check in this directory, so this is not cosmetic.
      2. `ConvertTo-Json` in 5.1 serialises a .NET `object[]` that has
         been wrapped with the comma operator as
         `{"value":[...],"Count":n}` instead of as an array, which silently
         corrupts every JSON evidence document this repository writes.

      Building the result with `+= ,$item` avoids both: it produces a
      PowerShell-constructed array, empty cases included.
    #>
    param([object] $Value)
    $out = @()
    if ($null -eq $Value) { return ,$out }
    if ($Value -is [string]) { return ,@($Value) }
    if ($Value -is [System.Collections.IDictionary]) { return ,@($Value.Values) }
    foreach ($item in $Value) { $out += ,$item }
    return ,$out
}