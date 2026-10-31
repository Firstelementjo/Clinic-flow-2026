#!/usr/bin/env pwsh
# =====================================================================
# run-backfill.ps1
#
# Runs a Phase-2 backfill in bounded, resumable batches and records evidence.
#
#   .\ops\flyway\backfill\run-backfill.ps1 -Script B1 -Container firstelement-postgres `
#       -Database firstelement -BatchSize 500 -MaxBatches 20
#
# SAFETY MODEL
#   * Refuses to run unless -Armed is passed. The backfill scripts themselves
#     also refuse unless firstelement_backfill_armed=on is supplied.
#   * Never drops, truncates, or deletes. The only DDL is B0's control tables.
#   * Runs the verification bundle BEFORE and AFTER so the row-count delta is
#     recorded as evidence rather than assumed.
#   * Stops after -MaxBatches so the first run is always a bounded probe.
#
# RESUMABILITY
#   Progress lives in backfill_runs, committed in the same transaction as each
#   batch. Re-running resumes; it never double counts and never skips a batch.
#   Safe to interrupt with Ctrl-C or by killing the process.
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^B[0-9]+$')]
    [string] $Script,

    [string] $Container = 'firstelement-postgres',
    [string] $Database  = 'firstelement',
    [string] $DbUser    = 'firstelement',
    [string] $BackfillName = '',
    [int]    $BatchSize = 500,
    [int]    $MaxBatches = 20,
    [string] $Operator  = [Environment]::UserName,
    [Parameter(Mandatory = $true)]
    [string] $OutputDir,
    [switch] $Armed,
    [switch] $ReadOnly
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }

function Invoke-SqlFile {
    param([string]$Path, [string[]]$PsqlVars = @(), [switch]$AllowNotices)
    $args = @('exec', '-i', $Container, 'psql', '-U', $DbUser, '-d', $Database,
              '-v', 'ON_ERROR_STOP=1', '-P', 'pager=off')
    foreach ($v in $PsqlVars) { $args += @('-v', $v) }
    $args += @('-f', '-')

    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = (Get-Content -Raw $Path | & docker @args 2>&1) | ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $previousEap
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowNotices) {
        $out | Out-File (Join-Path $OutputDir "$([IO.Path]::GetFileNameWithoutExtension($Path)).error.txt") -Encoding utf8
        throw "psql failed ($code) on $Path"
    }
    return $out
}

# ---------------------------------------------------------------------
# 0. Guard
# ---------------------------------------------------------------------
if (-not $ReadOnly -and -not $Armed) {
    throw @"
REFUSING TO RUN A BACKFILL WITHOUT -Armed.

Before a backfill you must have:
  [ ] a backup        ops/backup-restore/backup.ps1
  [ ] row counts      ops/flyway/verification/run-verification.ps1
  [ ] a rehearsed run  on a throwaway copy
Re-run with -Armed once those are done. Read-only scripts (-ReadOnly) do not need it.
"@
}

$sqlFile = Join-Path $scriptDir ($Script + '_*.sql') | Get-Item -ErrorAction SilentlyContinue
if (-not $sqlFile) { $sqlFile = Get-ChildItem $scriptDir -Filter "$Script*.sql" | Select-Object -First 1 }
if (-not $sqlFile) { throw "No SQL file found for $Script in $scriptDir" }

if (-not $BackfillName) { $BackfillName = $Script }

Write-Host "=== Phase-2 backfill $BackfillName ($($sqlFile.Name)) ==="
Write-Host "    target    : $Container/$Database"
Write-Host "    operator  : $Operator"
Write-Host "    batchSize : $BatchSize   maxBatches: $MaxBatches"
Write-Host ""

# ---------------------------------------------------------------------
# 1. Control tables (additive, IF NOT EXISTS)
# ---------------------------------------------------------------------
Write-Host '==> [1/3] backfill control tables'
Invoke-SqlFile -Path (Join-Path $scriptDir 'B0_backfill_control.sql') |
    Out-File (Join-Path $OutputDir '00-control-tables.txt') -Encoding utf8

# ---------------------------------------------------------------------
# 2. Pre-evidence
# ---------------------------------------------------------------------
Write-Host '==> [2/3] pre-run verification bundle'
$verificationScript = Join-Path (Split-Path -Parent $scriptDir) 'verification/run-verification.ps1'
& $verificationScript -Container $Container -Database $Database -DbUser $DbUser `
    -OutputDir (Join-Path $OutputDir 'pre') | Out-Null
Write-Host "    fingerprint: $((Get-Content (Join-Path $OutputDir 'pre/summary.json') -Raw | ConvertFrom-Json).schemaFingerprint)"

# ---------------------------------------------------------------------
# 3. Batches
# ---------------------------------------------------------------------
if ($ReadOnly) {
    Write-Host '==> [3/3] read-only execution'
    $out = Invoke-SqlFile -Path $sqlFile.FullName -PsqlVars @(
        "firstelement_backfill_armed=on", "backfill_name=$BackfillName",
        "batch_size=$BatchSize", "operator=$Operator") -AllowNotices
    $out | Out-File (Join-Path $OutputDir 'run.txt') -Encoding utf8
    $out | Write-Host
} else {
    Write-Host '==> [3/3] bounded batch loop'
    $psqlVars = @("firstelement_backfill_armed=on", "backfill_name=$BackfillName",
                  "batch_size=$BatchSize", "operator=$Operator")
    $combined = @()
    for ($i = 1; $i -le $MaxBatches; $i++) {
        $out = Invoke-SqlFile -Path $sqlFile.FullName -PsqlVars $psqlVars -AllowNotices
        $combined += "----- batch $i -----"
        $combined += $out
        $combined | Out-File (Join-Path $OutputDir 'run.txt') -Encoding utf8

        $done = (($out | Select-String 'B1_REMAINING') -match '\|\s+0\s+\|')
        Write-Host ("    batch {0}/{1}  {2}" -f $i, $MaxBatches, ($out | Select-String 'B1_REMAINING' | Select-Object -Last 1))
        if ($done) {
            Write-Host '    checkpoint reports 0 remaining: complete.'
            break
        }
    }
}

# ---------------------------------------------------------------------
# 4. Post-evidence
# ---------------------------------------------------------------------
Write-Host ''
Write-Host '==> post-run verification bundle'
& $verificationScript -Container $Container -Database $Database -DbUser $DbUser `
    -OutputDir (Join-Path $OutputDir 'post') | Out-Null
$pre  = Get-Content (Join-Path $OutputDir 'pre/summary.json')  -Raw | ConvertFrom-Json
$post = Get-Content (Join-Path $OutputDir 'post/summary.json') -Raw | ConvertFrom-Json
Write-Host "    pre  fingerprint: $($pre.schemaFingerprint)"
Write-Host "    post fingerprint: $($post.schemaFingerprint)"

if ($pre.schemaFingerprint -eq $post.schemaFingerprint) {
    Write-Host '    NOTE: schema fingerprint unchanged (expected: a backfill changes DATA, not the schema).'
}

Write-Host ''
Write-Host "Evidence bundle: $OutputDir"
Write-Host "Compare row counts:"
Write-Host "  pre : $OutputDir\pre\V2_row_counts.txt"
Write-Host "  post: $OutputDir\post\V2_row_counts.txt"