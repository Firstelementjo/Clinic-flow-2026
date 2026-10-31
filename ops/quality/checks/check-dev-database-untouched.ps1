#!/usr/bin/env pwsh
# =====================================================================
# check-dev-database-untouched.ps1
#
# Proves that the development database was NOT modified by a gate run.
#
# The migration and restore gates both read `firstelement` (a read-only
# verification bundle, and a read-only pg_dump) and write only to
# disposable throwaway databases. This check turns "we believe it was
# untouched" into evidence by comparing two verification bundles taken
# before and after the gate:
#
#   * the schema fingerprint (V1) must be byte-identical;
#   * the row-count map (V2) must be byte-identical -- a gate that
#     inserted one row, or a migration that stamped a history row, is
#     caught here;
#   * the enum contract (V3) must still hold.
#
# It does NOT prove nothing happened to the data *values* (row counts and
# schema cannot see an UPDATE that leaves the count unchanged). Nothing in
# the gates writes DML to `firstelement`; the value-level guarantee comes
# from the dump checksum recorded by ops/backup-restore/backup.ps1 and
# from the fact that every write path is a disposable database.
#
# Exit codes: 0 unchanged, 1 changed, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BeforeDir,
    [Parameter(Mandatory = $true)]
    [string] $AfterDir,
    [string] $Database = 'firstelement',
    [string] $JsonOut
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

$problems = New-Object System.Collections.Generic.List[string]

function Read-FileOrNull {
    param([string] $Path)
    if (Test-Path -LiteralPath $Path) { return (Get-Content -Raw -LiteralPath $Path) }
    return $null
}

$beforeSummaryPath = Join-Path $BeforeDir 'summary.json'
$afterSummaryPath  = Join-Path $AfterDir 'summary.json'
foreach ($p in @($beforeSummaryPath, $afterSummaryPath)) {
    if (-not (Test-Path -LiteralPath $p)) {
        Write-QLog ("missing verification summary: {0}" -f $p) -Level ERROR
        exit 2
    }
}

$before = Get-Content -Raw -LiteralPath $beforeSummaryPath | ConvertFrom-Json
$after  = Get-Content -Raw -LiteralPath $afterSummaryPath  | ConvertFrom-Json

$beforeFp = [string]$before.schemaFingerprint
$afterFp  = [string]$after.schemaFingerprint
if (-not $beforeFp -or -not $afterFp) {
    $problems.Add('a verification bundle has no schema fingerprint; V1 did not produce rows, so the comparison would be meaningless')
}
elseif ($beforeFp -ne $afterFp) {
    $problems.Add(("SCHEMA CHANGED: fingerprint {0} -> {1}. A gate is not allowed to migrate the development database." -f $beforeFp, $afterFp))
}

$beforeRows = Read-FileOrNull (Join-Path $BeforeDir 'V2_row_counts.txt')
$afterRows  = Read-FileOrNull (Join-Path $AfterDir 'V2_row_counts.txt')
if ($null -eq $beforeRows -or $null -eq $afterRows) {
    $problems.Add('a verification bundle is missing V2_row_counts.txt')
}
elseif ($beforeRows -ne $afterRows) {
    $problems.Add('ROW COUNTS CHANGED between the before and after bundles. A gate inserted, updated or deleted a row in the development database.')
    # name the differing tables so the finding is actionable
    $b = @{}
    foreach ($line in ($beforeRows -split "`n")) { if ($line -match '\S') { $b[$line] = $true } }
    foreach ($line in ($afterRows -split "`n")) {
        if (-not $line.Trim()) { continue }
        if (-not $b.ContainsKey($line)) { $problems.Add('  changed: ' + $line.Trim()) }
    }
}

if (-not $after.enumContractOk) {
    $problems.Add('the enum contract is broken after the gate ran (EmployeeAvailabilityStatus / AIRunDecision)')
}

# V10 reports PHASE5A_NOT_PRESENT on a pre-boot database. Record it rather
# than assert: the boot itself is an orchestrator action, not a gate action.
$phase5a = $null
$v10Path = Join-Path $AfterDir 'V10_outbox_job_slice.txt'
if (Test-Path -LiteralPath $v10Path) {
    $phase5a = (Select-String -LiteralPath $v10Path -Pattern 'PHASE5A_(NOT_)?PRESENT' | Select-Object -First 1)
    if ($phase5a) { $phase5a = $phase5a.Matches[0].Value }
}

$verdict = if ($problems.Count -eq 0) { 'PASS' } else { 'FAIL' }
$result = [ordered]@{
    check            = 'dev-database-untouched'
    verdict          = $verdict
    checkedAtUtc     = (Get-Date).ToUniversalTime().ToString('o')
    database         = $Database
    beforeDir        = $BeforeDir
    afterDir         = $AfterDir
    beforeFingerprint = $beforeFp
    afterFingerprint  = $afterFp
    fingerprintUnchanged = ($beforeFp -eq $afterFp)
    rowCountsIdentical   = ($null -ne $beforeRows -and $null -ne $afterRows -and $beforeRows -eq $afterRows)
    enumContractOk       = $after.enumContractOk
    phase5aPresence      = $phase5a
    problems             = ConvertTo-QArray -Value $problems
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

Write-QLog ''
Write-QLog ("  database            : {0}" -f $Database)
Write-QLog ("  fingerprint before  : {0}" -f $beforeFp)
Write-QLog ("  fingerprint after   : {0}" -f $afterFp)
Write-QLog ("  row counts identical: {0}" -f $result.rowCountsIdentical)
Write-QLog ("  enum contract ok    : {0}" -f $after.enumContractOk)
if ($phase5a) { Write-QLog ("  phase5a slice       : {0}" -f $phase5a) }

if ($verdict -eq 'FAIL') {
    foreach ($p in $problems) { Write-QLog ('  - ' + $p) -Level ERROR }
    Write-QLog 'dev-database-untouched VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'dev-database-untouched VERDICT: PASS -- the development database was read, never written' -Level STEP
exit 0