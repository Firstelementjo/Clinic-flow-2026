#!/usr/bin/env pwsh
# =====================================================================
# prepare-restore-rehearsal.ps1
#
# Picks the newest dump out of a backup directory, refuses to target
# anything that is not obviously disposable, then calls the existing
# ops/backup-restore/restore-rehearsal.ps1 and propagates its verdict.
#
# Why a wrapper instead of calling restore-rehearsal.ps1 directly from the
# catalogue: the dump filename is a backup timestamp, so a stage
# definition cannot name it, and resolving "newest dump" inside the stage
# definition would need stdout capture that no stage can express. The
# refusal logic lives here so it is reviewable in one place instead of
# being spread across a catalogue entry.
#
# Exit codes: 0 rehearsal passed, 1 refused or the rehearsal failed,
# 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BackupDir,
    [Parameter(Mandatory = $true)]
    [string] $EvidenceDir,
    [string] $TargetDatabase = 'firstelement_restore_check',
    [string] $SourceDatabase = 'firstelement',
    [string] $Container = 'firstelement-postgres',
    [string] $RepoRoot
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$repoRootResolved = (Resolve-Path -LiteralPath $RepoRoot).Path

# --- refusals, before anything is opened ------------------------------
if ($TargetDatabase -eq $SourceDatabase) {
    Write-QLog ("REFUSING: -TargetDatabase equals -SourceDatabase ({0}). A rehearsal must never target the database it rehearses from." -f $TargetDatabase) -Level ERROR
    exit 1
}
if ($TargetDatabase -notmatch '(?i)(restore_check|rehearsal)') {
    Write-QLog ("REFUSING: '{0}' does not look disposable. A restore rehearsal target must contain 'restore_check' or 'rehearsal'." -f $TargetDatabase) -Level ERROR
    exit 1
}

if (-not (Test-Path -LiteralPath $BackupDir)) {
    Write-QLog ("backup directory not found: {0}. Run ops/backup-restore/backup.ps1 first; a restore rehearsal without a dump would be theatre." -f $BackupDir) -Level ERROR
    exit 1
}

$dumps = @(Get-ChildItem -LiteralPath $BackupDir -Filter '*.dump' -File -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
if ($dumps.Count -eq 0) {
    Write-QLog ("no *.dump in {0}" -f $BackupDir) -Level ERROR
    exit 1
}
$dump = $dumps[0]
$sha = (Get-FileHash -LiteralPath $dump.FullName -Algorithm SHA256).Hash.ToLower()
Write-QLog ("newest dump : {0}" -f $dump.Name)
Write-QLog ("dump sha256 : {0}" -f $sha)
Write-QLog ("target      : {0} (DISPOSABLE, refused above if not)" -f $TargetDatabase)

$rehearsal = Join-Path $repoRootResolved ('ops' + [string][char]92 + 'backup-restore' + [string][char]92 + 'restore-rehearsal.ps1')
if (-not (Test-Path -LiteralPath $rehearsal)) {
    Write-QLog ("restore-rehearsal.ps1 not found at {0}" -f $rehearsal) -Level ERROR
    exit 2
}

# The rehearsal script's own exit code is the verdict; it exits 1 on a
# fingerprint/row-count mismatch. A failed restore is a failed gate
# (plan.md rule 10).
& $rehearsal -Dump $dump.FullName -EvidenceDir $EvidenceDir -Container $Container `
    -SourceDatabase $SourceDatabase -TargetDatabase $TargetDatabase -Dispose
$code = $LASTEXITCODE

if ($code -ne 0) {
    Write-QLog ("RESTORE REHEARSAL FAILED (exit {0}). Evidence: {1}" -f $code, $EvidenceDir) -Level ERROR
    exit 1
}
Write-QLog ("restore rehearsal PASS. Evidence: {0}" -f $EvidenceDir) -Level STEP
exit 0