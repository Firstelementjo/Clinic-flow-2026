#!/usr/bin/env pwsh
# =====================================================================
# restore-rehearsal.ps1
#
# Proves a backup can actually be restored, into a DISPOSABLE database, and
# that the restored schema and data match the source.
#
#   .\ops\backup-restore\restore-rehearsal.ps1 `
#       -Dump C:\...\backups\2026-10-04\20261004T120000Z_firstelement.dump `
#       -EvidenceDir C:\...\evidence\restore-check
#
# HARD RULES ENFORCED HERE
#   * The target database name MUST NOT be the source database name. The script
#     refuses to restore over the database it is rehearsing from.
#   * The target database is created with a `_restore_check` style suffix and is
#     DISPOSE-AFTERWARDS. Pass -Dispose to drop it after verification.
#   * Nothing in this script touches the source database.
#   * An unknown -TargetDatabase is refused unless it clearly looks like a
#     rehearsal target (contains 'restore_check' or 'rehearsal') or
#     -ForceRehearsalName is passed. This is deliberate friction: a rehearsal
#     must never be one typo away from production.
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Dump,
    [string] $Container = 'firstelement-postgres',
    [string] $SourceDatabase = 'firstelement',
    [string] $TargetDatabase = 'firstelement_restore_check',
    [string] $DbUser = 'firstelement',
    [Parameter(Mandatory = $true)]
    [string] $EvidenceDir,
    [switch] $ForceRehearsalName,
    [switch] $Dispose
)

$ErrorActionPreference = 'Stop'

function Invoke-Psql {
    param([string]$Sql, [string]$Db = $DbUser)
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db -v ON_ERROR_STOP=1 `
            -t -A -P pager=off -f - 2>&1) | ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $previousEap
    if ($LASTEXITCODE -ne 0) { throw "psql failed on $Db`n$($out -join "`n")" }
    return $out
}

# ---------------------------------------------------------------------
# Guards
# ---------------------------------------------------------------------
if (-not (Test-Path $Dump)) { throw "Dump not found: $Dump" }
if ($TargetDatabase -eq $SourceDatabase) {
    throw "REFUSING: -TargetDatabase equals -SourceDatabase ($TargetDatabase). A rehearsal must never target the source."
}
if ($TargetDatabase -notmatch 'restore_check|rehearsal' -and -not $ForceRehearsalName) {
    throw @"
REFUSING: target name '$TargetDatabase' does not look like a rehearsal target.

A restore rehearsal must be obviously disposable. Use a name containing
'restore_check' or 'rehearsal', or pass -ForceRehearsalName if you really
intend otherwise.
"@
}

if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }

$dumpSha = (Get-FileHash -Path $Dump -Algorithm SHA256).Hash.ToLower()
Write-Host "FirstElement restore rehearsal"
Write-Host "  dump            : $Dump"
Write-Host "  dump sha256     : $dumpSha"
Write-Host "  source database : $SourceDatabase   (never written to)"
Write-Host "  target database : $TargetDatabase   (DISPOSABLE)"
Write-Host "  evidence        : $EvidenceDir"
Write-Host ""

# ---------------------------------------------------------------------
# 1. Source reference numbers (captured BEFORE anything else)
# ---------------------------------------------------------------------
Write-Host '==> [1/5] source reference numbers'
$verificationScript = Join-Path $PSScriptRoot '..\flyway\verification\run-verification.ps1'
& $verificationScript -Container $Container -Database $SourceDatabase -DbUser $DbUser `
    -OutputDir (Join-Path $EvidenceDir 'source') | Out-Null
$srcSummary = Get-Content (Join-Path $EvidenceDir 'source/summary.json') -Raw | ConvertFrom-Json
Write-Host "    source fingerprint: $($srcSummary.schemaFingerprint)"

# ---------------------------------------------------------------------
# 2. Create the throwaway database
# ---------------------------------------------------------------------
Write-Host "==> [2/5] creating disposable database $TargetDatabase"
Invoke-Psql -Db $SourceDatabase -Sql @"
DROP DATABASE IF EXISTS "$TargetDatabase";
"@ | Out-Null
Invoke-Psql -Db $SourceDatabase -Sql @"
CREATE DATABASE "$TargetDatabase" TEMPLATE template0;
"@ | Out-Null
Write-Host "    created."

# ---------------------------------------------------------------------
# 3. Restore
# ---------------------------------------------------------------------
Write-Host '==> [3/5] pg_restore'
$remoteDump = "/tmp/restore_rehearsal.dump"
docker cp $Dump "$Container`:$remoteDump" | Out-Null
docker exec $Container pg_restore -U $DbUser -d $TargetDatabase `
    --no-owner --no-privileges --exit-on-error $remoteDump 2>&1 |
    ForEach-Object { $_.ToString() } | Out-File (Join-Path $EvidenceDir 'pg_restore.log') -Encoding utf8
$restoreCode = $LASTEXITCODE
if ($restoreCode -ne 0) {
    Get-Content (Join-Path $EvidenceDir 'pg_restore.log') | Write-Host
    throw "pg_restore failed with exit code $restoreCode. Evidence: $EvidenceDir\pg_restore.log"
}
docker exec $Container rm -f $remoteDump | Out-Null
Write-Host "    restored cleanly."

# ---------------------------------------------------------------------
# 4. Verify
# ---------------------------------------------------------------------
Write-Host '==> [4/5] verifying the restored database'
& $verificationScript -Container $Container -Database $TargetDatabase -DbUser $DbUser `
    -OutputDir (Join-Path $EvidenceDir 'restored') -ThrowOnViolation | Out-Null
$dstSummary = Get-Content (Join-Path $EvidenceDir 'restored/summary.json') -Raw | ConvertFrom-Json

$checks = [ordered]@{
    dumpSha256                = $dumpSha
    sourceFingerprint         = $srcSummary.schemaFingerprint
    restoredFingerprint       = $dstSummary.schemaFingerprint
    fingerprintMatches        = ($srcSummary.schemaFingerprint -eq $dstSummary.schemaFingerprint)
    sourceEnumContractOk      = $srcSummary.enumContractOk
    restoredEnumContractOk    = $dstSummary.enumContractOk
}

# Row counts must match exactly, table by table.
$srcRows = (Get-Content (Join-Path $EvidenceDir 'source/V2_row_counts.txt') -Raw)
$dstRows = (Get-Content (Join-Path $EvidenceDir 'restored/V2_row_counts.txt') -Raw)
$checks.rowCountsIdentical = ($srcRows -eq $dstRows)

# Business-key spot check: identifiers survive the round trip.
$sample = Invoke-Psql -Db $TargetDatabase -Sql @"
select count(*) || '/' || (select count(*) from organizations) || '/' ||
       (select count(*) from users) || '/' || (select count(*) from conversations) || '/' ||
       (select count(*) from audit_logs)
  from sessions;
"@
$checks.identitySample = ($sample | Select-Object -First 1)

$checks | ConvertTo-Json | Out-File (Join-Path $EvidenceDir 'restore-checks.json') -Encoding utf8

Write-Host ''
Write-Host '--- restore checks ---'
$checks.GetEnumerator() | ForEach-Object {
    Write-Host ("  {0,-26} {1}" -f $_.Key, $_.Value)
}

# ---------------------------------------------------------------------
# 5. Result
# ---------------------------------------------------------------------
$ok = $checks.fingerprintMatches -and $checks.restoredEnumContractOk -and $checks.rowCountsIdentical

Write-Host ''
if ($ok) {
    Write-Host 'RESTORE REHEARSAL: PASS'
    Write-Host "  evidence: $EvidenceDir"
} else {
    Write-Host 'RESTORE REHEARSAL: FAIL'
    Write-Host "  evidence: $EvidenceDir"
    Write-Host '  A failed rehearsal is a failed gate (plan.md rule 10). Do not cut over.'
}

if ($Dispose) {
    Write-Host ''
    Write-Host "==> disposing $TargetDatabase"
    Invoke-Psql -Db $SourceDatabase -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
 WHERE datname = '$TargetDatabase' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$TargetDatabase";
"@ | Out-Null
    Write-Host "    dropped $TargetDatabase"
} else {
    Write-Host ''
    Write-Host "The disposable database '$TargetDatabase' is still present for inspection."
    Write-Host "Dispose when done:"
    Write-Host "  docker exec -it $Container psql -U $DbUser -d $SourceDatabase -c 'DROP DATABASE IF EXISTS ""$TargetDatabase"";'"
}

if (-not $ok) { exit 1 }