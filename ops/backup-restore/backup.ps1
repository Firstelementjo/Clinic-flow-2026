#!/usr/bin/env pwsh
# =====================================================================
# backup.ps1
#
# FirstElement database backup. Produces BOTH a restorable custom-format dump
# and a plain-SQL dump, plus checksums and a verification bundle, so the backup
# is evidence rather than an assumption.
#
#   .\ops\backup-restore\backup.ps1 -OutputDir C:\...\backups\2026-10-04
#
# PROPERTIES
#   * READ-ONLY against the database (pg_dump only).
#   * pg_dump -Fc is the restore format (parallel-restorable, selective).
#     The plain -Fp dump is kept for grep/diff and for platforms without
#     pg_restore.
#   * SHA-256 of every artifact is recorded in manifest.json.
#   * Row counts, schema fingerprint and enum contract are captured at backup
#     time so a later restore can be proven against the same numbers.
#   * No credentials are read, printed, or written. The dump is taken through
#     `docker exec` against the container's local socket.
#
# ENCRYPTION
#   PROJECT_REQUIREMENTS §7 requires encrypted backups. This script does NOT
#   encrypt: encryption keys must never live in the repository or in a script.
#   Encrypt at rest with the platform's storage encryption (volume/disk) and,
#   for off-host copies, with an externally supplied key:
#     age    -r <recipient> -o dump.dump.age dump.dump
#     gpg    --encrypt --recipient ops-backup@firstelement -o dump.dump.gpg dump.dump
#   Document which mechanism is in use in the Phase-2 report.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $Database  = 'firstelement',
    [string] $DbUser    = 'firstelement',
    [Parameter(Mandatory = $true)]
    [string] $OutputDir,
    [string[]] $AlsoDatabase = @()
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }

$stamp   = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$targets = @($Database) + $AlsoDatabase | Select-Object -Unique
$manifest = [ordered]@{
    createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    host         = $Container
    serverVersion = $null
    artifacts    = @()
    databases    = @()
}

$serverVersion = (docker exec $Container psql -U $DbUser -d $Database -t -A -c "show server_version" |
                  Select-Object -First 1).Trim()
$manifest.serverVersion = $serverVersion

Write-Host "FirstElement database backup"
Write-Host "  container      : $Container"
Write-Host "  server version : $serverVersion"
Write-Host "  databases      : $($targets -join ', ')"
Write-Host "  output         : $OutputDir"
Write-Host ""

foreach ($db in $targets) {
    $base = "$stamp`_$db"

    # --- custom format (the restorable one) --------------------------
    $customName = "$base.dump"
    Write-Host "==> pg_dump -Fc $db -> $customName"
    docker exec $Container pg_dump -U $DbUser -d $db -Fc --no-owner --no-privileges `
        --file="/tmp/$customName"
    if ($LASTEXITCODE -ne 0) { throw "pg_dump -Fc failed for $db" }
    docker cp "$Container`:/tmp/$customName" (Join-Path $OutputDir $customName) | Out-Null
    docker exec $Container rm -f "/tmp/$customName" | Out-Null

    # --- plain SQL (inspection / portable restore) --------------------
    $plainName = "$base.sql"
    Write-Host "==> pg_dump -Fp $db -> $plainName"
    docker exec $Container pg_dump -U $DbUser -d $db -Fp --no-owner --no-privileges `
        --file="/tmp/$plainName"
    if ($LASTEXITCODE -ne 0) { throw "pg_dump -Fp failed for $db" }
    docker cp "$Container`:/tmp/$plainName" (Join-Path $OutputDir $plainName) | Out-Null
    docker exec $Container rm -f "/tmp/$plainName" | Out-Null

    # --- global objects (roles/tablespaces) --------------------------
    $globName = "$base-globals.sql"
    Write-Host "==> pg_dumpall --globals-only"
    $globals = docker exec $Container pg_dumpall -U $DbUser --globals-only --no-role-passwords 2>&1 |
               ForEach-Object { $_.ToString() }
    if ($LASTEXITCODE -ne 0) { throw "pg_dumpall --globals-only failed" }
    $globals | Out-File (Join-Path $OutputDir $globName) -Encoding utf8

    foreach ($name in @($customName, $plainName, $globName)) {
        $path = Join-Path $OutputDir $name
        $manifest.artifacts += [ordered]@{
            file   = $name
            bytes  = (Get-Item $path).Length
            sha256 = (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLower()
        }
    }
    $manifest.databases += [ordered]@{
        database = $db
        dump     = $customName
        sql      = $plainName
    }
}

# --- verification bundle at backup time ------------------------------
# Captured so a restore can be proven against identical numbers.
Write-Host ""
Write-Host "==> verification bundle (pre-restore reference)"
$verificationScript = Join-Path $PSScriptRoot '..\flyway\verification\run-verification.ps1'
if (Test-Path $verificationScript) {
    & $verificationScript -Container $Container -Database $Database -DbUser $DbUser `
        -OutputDir (Join-Path $OutputDir 'verification') | Out-Null
    $s = Get-Content (Join-Path $OutputDir 'verification/summary.json') -Raw | ConvertFrom-Json
    $manifest.schemaFingerprint = $s.schemaFingerprint
    $manifest.enumContractOk    = $s.enumContractOk
    Write-Host "  schema fingerprint : $($s.schemaFingerprint)"
    Write-Host "  enum contract ok   : $($s.enumContractOk)"
    $manifest.databases[0] | Add-Member -NotePropertyName verification -NotePropertyValue 'verification/'
} else {
    Write-Warning "verification runner not found at $verificationScript"
}

$manifest | ConvertTo-Json -Depth 6 |
    Out-File (Join-Path $OutputDir 'manifest.json') -Encoding utf8

Write-Host ""
Write-Host "Backup complete: $OutputDir"
Get-Content (Join-Path $OutputDir 'manifest.json')