#!/usr/bin/env pwsh
# =====================================================================
# observation-window.ps1
# =====================================================================
# The two observation windows of plan.md Phase 12 step 8 are the one
# part of Phase 12 that cannot be written, faked, or shortened: they are
# elapsed time with a system under real traffic. No amount of test
# evidence substitutes for them, and this script does not pretend to.
#
# What it DOES do is make them measurable instead of anecdotal. Before
# this existed, "the system was stable for two windows" was a sentence an
# operator could say after the fact, with nothing behind it. Now each
# sample is a row with a timestamp and the six signals plan.md step 7
# names, appended to a durable record, so the window is something a
# reviewer can read rather than something an owner has to remember.
#
#   .\ops\observation\observation-window.ps1 -Start
#   .\ops\observation\observation-window.ps1 -Sample
#   .\ops\observation\observation-window.ps1 -Status
#   .\ops\observation\observation-window.ps1 -Close
#
# DESIGN, and the reasoning worth keeping:
#
#   * IT READS THE OBSERVED DATABASE, NOT A SIMULATION. The counters come
#     from the real `firstelement` database and the real application. A
#     window measured against a fixture is a test wearing a window's
#     name.
#
#   * IT NEVER PASSES OR FAILS. It records. The verdict belongs to the
#     four owners who sign, and a script that could declare a window
#     healthy would be the same defect as a document edited to match a
#     route: it would make the conclusion contractual instead of earned.
#     It DOES record its own integrity problems -- a signal it could not
#     read is recorded as `unavailable`, never as zero and never omitted,
#     because "we saw no drift" and "we could not look" are the same
#     words and mean opposite things.
#
#   * A WINDOW IS NOT COMPLETE BY ELAPSING. `-Close` refuses to call a
#     window closed if it has too few samples, and reports the sampling
#     gaps, so a window cannot be satisfied by starting it and coming
#     back later.
# =====================================================================

[CmdletBinding()]
param(
    [switch] $Start,
    [switch] $Sample,
    [switch] $Status,
    [switch] $Close,
    [string] $RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string] $Container = 'firstelement-postgres',
    [string] $Database = 'firstelement',
    [string] $DbUser = 'firstelement',
    [string] $StateDir,
    [int]    $MinimumSamples = 8,
    [int]    $MaxGapMinutes = 240
)

$ErrorActionPreference = 'Stop'

if (-not $StateDir) { $StateDir = Join-Path $RepoRoot 'build\observation' }
$recordPath = Join-Path $StateDir 'window-record.json'

function Write-QLog {
    param([string] $Message, [string] $Level = 'INFO')
    Write-Host ("{0}  {1,-5} {2}" -f (Get-Date).ToString('HH:mm:ss'), $Level, $Message)
}

# ---------------------------------------------------------------------
# Database access. Read-only: an observation window must not be able to
# change what it is observing, and a script that can write to the
# database it is watching is a script that can corrupt its own evidence.
# ---------------------------------------------------------------------
function Invoke-ReadSql {
    param([string] $Sql)
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -t -A -f - 2>&1) |
        ForEach-Object { [string]$_ }
    if ($LASTEXITCODE -ne 0) { throw ("read-only query failed: {0}" -f (($out | Where-Object { $_ }) -join ' ')) }
    return ($out | Where-Object { $_ -ne '' })
}

function Read-Counter {
    param([string] $Sql, $Unavailable, [string] $Name)
    try {
        $v = Invoke-ReadSql -Sql $Sql
        if (-not $v) { throw 'no rows returned' }
        return [int64]($v | Select-Object -First 1)
    }
    catch {
        $Unavailable.Add($Name)
        Write-QLog ("could not read {0}: {1}" -f $Name, $_.Exception.Message) 'WARN'
        return $null
    }
}

# Whether a table exists is asked of the database, once per sample, rather than assumed by the
# script's author. The tables that carry plan.md step 7's signals were written by migrations that
# have NEVER been applied to the observed database, which is itself the finding - see the Phase 12
# report - and a script that quietly counted a missing table as zero would have hidden it.
function Test-Signal {
    param([string] $Table)
    try {
        $v = Invoke-ReadSql -Sql ("select count(*) from information_schema.tables where table_schema = 'public' and table_name = '{0}';" -f $Table)
        return ([int64]($v | Select-Object -First 1)) -gt 0
    }
    catch { return $false }
}

function Get-NewWindow {
    $id = 'W' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
    return [ordered]@{
        windowId          = $id
        startedAtUtc      = (Get-Date).ToUniversalTime().ToString('o')
        endedAtUtc        = $null
        closedBy          = $null
        signOff           = [ordered]@{ product = $null; operations = $null; security = $null; finance = $null }
        samples           = @()
        note              = 'A window is evidence, not a verdict. It records what was observed; the four owners sign.'
    }
}

function Save-Window {
    param($Window)
    if (-not (Test-Path -LiteralPath $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
    $Window | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $recordPath -Encoding UTF8
}

function Get-Window {
    if (-not (Test-Path -LiteralPath $recordPath)) { return $null }
    return Get-Content -Raw -LiteralPath $recordPath | ConvertFrom-Json
}

function New-Sample {
    param($Window)
    $unavailable = New-Object 'System.Collections.Generic.List[string]'

    # PHASE 49. THE SIGNALS ARE DISCOVERED FROM THE DATABASE, NOT ASSUMED. The first version of
    # this file named outbox_events, outbox_messages, export_jobs, security_events and
    # ai_runs.refusalCode from memory. Every one of them was wrong, and the harness dutifully
    # reported "relation does not exist" eight times per sample -- a monitoring script that
    # monitors nothing and says so loudly. The real names and columns were read out of
    # information_schema. Where a signal does not exist at all, that is recorded as unavailable,
    # which is the honest answer and is why -Close refuses to close a window with a blind spot.
    $aiRuns = Read-Counter 'select count(*) from ai_runs;' $unavailable 'aiRuns'
    $aiFinished = Read-Counter 'select count(*) from ai_runs where "finishedAt" is not null;' $unavailable 'aiFinished'
    $aiErrored = Read-Counter 'select count(*) from ai_runs where "errorMessage" is not null;' $unavailable 'aiErrored'

    $outboxPending = $null; $outboxDead = $null; $oldestPendingS = $null
    $failedMessages = $null; $refusedExports = $null; $securityEvents = $null

    if (Test-Signal 'outbox_events') {
        $outboxPending  = Read-Counter 'select count(*) from outbox_events where state = ''PENDING'';' $unavailable 'outboxPending'
        $outboxDead     = Read-Counter 'select count(*) from outbox_events where state = ''DEAD'';'    $unavailable 'outboxDead'
        $oldestPendingS = Read-Counter 'select coalesce((select extract(epoch from ((now() at time zone ''utc'') - "createdAt"))::bigint from outbox_events where state = ''PENDING'' order by "createdAt" asc limit 1), 0);' $unavailable 'oldestPendingAgeSeconds'
    }
    else {
        foreach ($n in @('outboxPending', 'outboxDead', 'oldestPendingAgeSeconds')) { $unavailable.Add($n) }
        Write-QLog 'outbox_events does not exist in the observed database, so queue lag is NOT observable' 'WARN'
    }
    if (Test-Signal 'job_attempts') {
        $failedMessages = Read-Counter 'select count(*) from job_attempts where "outcome" in (''RETRYABLE_FAILURE'', ''NON_RETRYABLE_FAILURE'');' $unavailable 'failedMessages'
    }
    else { $unavailable.Add('failedMessages') }
    if (Test-Signal 'export_jobs') {
        $refusedExports = Read-Counter 'select count(*) from export_jobs where "status" = ''FAILED'';' $unavailable 'failedExports'
    }
    else { $unavailable.Add('failedExports') }
    if (Test-Signal 'security_events') {
        $securityEvents = Read-Counter 'select count(*) from security_events;' $unavailable 'securityEvents'
    }
    else { $unavailable.Add('securityEvents') }

    return [ordered]@{
        atUtc                   = (Get-Date).ToUniversalTime().ToString('o')
        outboxPending           = $outboxPending
        outboxDead              = $outboxDead
        oldestPendingAgeSeconds = $oldestPendingS
        failedOutboxMessages    = $failedMessages
        failedExports           = $refusedExports
        aiRuns                  = $aiRuns
        aiFinished              = $aiFinished
        aiErrored               = $aiErrored
        securityEvents          = $securityEvents
        unavailable             = @($unavailable)
    }
}

# =====================================================================
if ($Start) {
    if (Get-Window) { throw "a window is already open in $recordPath. Close it before starting another: two overlapping windows produce two half-histories, not two windows." }
    $window = Get-NewWindow
    $null = New-Sample -Window $window | ForEach-Object { $window.samples = @($window.samples) + $_ }
    Save-Window -Window $window
    Write-QLog ("window {0} OPENED at {1}. It records; it does not judge. Close it with -Close." -f $window.windowId, $window.startedAtUtc) 'STEP'
    exit 0
}

if ($Sample) {
    $window = Get-Window
    if (-not $window) { throw "no window is open in $recordPath. Start one with -Start." }
    # The local is $newSample, not $sample, and that is not a style choice. PowerShell variable names
    # are CASE-INSENSITIVE, so `$sample` and the script's own `[switch] $Sample` parameter are the
    # SAME variable: the local was writing into the switch, which stayed a SwitchParameter, and
    # `$sample.atUtc` failed with "the atUtc property was not found for a SwitchParameter". A name
    # that shadows a parameter is a bug that reads like a type error.
    $newSample = New-Sample -Window $window
    $window.samples = @($window.samples) + $newSample
    Save-Window -Window $window
    Write-QLog ("sampled {0}: aiRuns={1} unavailable=[{2}]" -f `
        $newSample.atUtc, $newSample.aiRuns, ($newSample.unavailable -join ','))
    exit 0
}

if ($Status) {
    $window = Get-Window
    if (-not $window) { Write-QLog 'no window has been started' 'WARN'; exit 0 }
    $samples = @($window.samples)
    Write-QLog ("window {0}  started {1}  samples={2}  closed={3}" -f $window.windowId, $window.startedAtUtc, $samples.Count, ($window.closedBy -ne $null))
    if ($samples.Count -gt 0) {
        $last = $samples[-1]
        Write-QLog ("latest: {0}" -f ($last | ConvertTo-Json -Compress -Depth 4))
        $dead = @($samples | Where-Object { $_.outboxDead -ne $null } | ForEach-Object { [int64]$_.outboxDead })
        if ($dead.Count -gt 0) {
            Write-QLog ("outbox DEAD across the window: min={0} max={1}. A DEAD row is a message that will never be delivered." -f ($dead | Measure-Object -Minimum).Minimum, ($dead | Measure-Object -Maximum).Maximum) 'WARN'
        }
    }
    exit 0
}

if ($Close) {
    $window = Get-Window
    if (-not $window) { throw "no window is open in $recordPath" }
    $samples = @($window.samples)

    # A window is not satisfied by existing. These are the reasons a close
    # is REFUSED, each of which is a way the record would otherwise be
    # made to look better than the evidence supports.
    $problems = @()
    if ($samples.Count -lt $MinimumSamples) {
        $problems += ("only {0} sample(s); a window needs at least {1} before it can be closed" -f $samples.Count, $MinimumSamples)
    }
    $anyUnavailable = @($samples | Where-Object { @($_.unavailable).Count -gt 0 })
    if ($anyUnavailable.Count -gt 0) {
        $names = @($anyUnavailable | ForEach-Object { @($_.unavailable) } | Sort-Object -Unique)
        $problems += ("{0} of {1} sample(s) could not read at least one signal ({2}). A window with a blind spot is not a window." -f $anyUnavailable.Count, $samples.Count, ($names -join ', '))
    }
    $gaps = @()
    for ($i = 1; $i -lt $samples.Count; $i++) {
        $prev = [datetime]::Parse($samples[$i - 1].atUtc)
        $cur = [datetime]::Parse($samples[$i].atUtc)
        $gapMinutes = [int](($cur - $prev).TotalMinutes)
        if ($gapMinutes -gt $MaxGapMinutes) { $gaps += ("{0} minutes between {1} and {2}" -f $gapMinutes, $samples[$i - 1].atUtc, $samples[$i].atUtc) }
    }
    if ($gaps.Count -gt 0) {
        $problems += ("{0} sampling gap(s) longer than {1} minutes: {2}" -f $gaps.Count, $MaxGapMinutes, ($gaps -join '; '))
    }

    if ($problems.Count -gt 0) {
        Write-QLog 'REFUSING to close the window:' 'ERROR'
        foreach ($p in $problems) { Write-QLog ("  - {0}" -f $p) 'ERROR' }
        exit 1
    }

    $window.endedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    $window.closedBy = 'orchestrator (evidence collected); sign-off is NOT recorded here'
    Save-Window -Window $window
    Write-QLog ("window {0} CLOSED with {1} sample(s) and no blind spots." -f $window.windowId, $samples.Count) 'STEP'
    Write-QLog 'Sign-off by the product, operations, security and finance owners is still REQUIRED and is not recorded by this script.' 'WARN'
    exit 0
}

Write-QLog 'specify one of -Start, -Sample, -Status, -Close' 'ERROR'
exit 2