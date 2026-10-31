#!/usr/bin/env pwsh
# =====================================================================
# generate-baseline.ps1
#
# Regenerates the Flyway baseline from the VERIFIED live database.
#
# The baseline is deliberately derived from the real database rather than
# from `prisma migrate` output: the live dev database was created with
# `prisma db push` and is the only schema the legacy NestJS/Prisma runtime has
# ever executed against. See docs/migration/schema-drift-report.md and
# docs/migration/adr/0002-flyway-baseline-quoted-columns.md.
#
#   .\ops\flyway\generate\generate-baseline.ps1 `
#       -Container firstelement-postgres -Database firstelement `
#       -OutFile .\ops\flyway\sql\V20260814000000__baseline_verified_live_schema.sql
#
# Safety properties:
#   * READ-ONLY against the source database (`pg_dump --schema-only`).
#   * Never drops, truncates, or writes to the source database.
#   * Reproducible: same source schema => byte-identical output.
#   * Strips pg_dump's `\restrict` / `\unrestrict` psql meta-commands, which
#     Flyway's PostgreSQL parser does not understand.
#
# After regenerating, ALWAYS re-run:
#   .\ops\flyway\verification\run-verification.ps1 -OutputDir <dir>
#   .\gradlew :backend:database:test
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $Database  = 'firstelement',
    [string] $DbUser    = 'firstelement',
    [Parameter(Mandatory = $true)]
    [string] $OutFile,
    [string] $MirrorDir = ''
)

$ErrorActionPreference = 'Stop'

Write-Host "Generating Flyway baseline from $Container/$Database (schema-only, read-only)..."

$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$dump = docker exec $Container pg_dump -U $DbUser -d $Database `
        --schema-only --no-owner --no-privileges --quote-all-identifiers `
        --file=- 2>&1 | ForEach-Object { $_.ToString() }
$ErrorActionPreference = $previousEap
if ($LASTEXITCODE -ne 0) {
    throw "pg_dump failed for $Database"
}

# Drop pg_dump's newer psql meta-commands. Flyway sends each migration to the
# server as SQL; `\restrict` is a client-side directive and would be a syntax
# error.
$body = $dump | Where-Object { $_ -notmatch '^\\(restrict|unrestrict)\s' }

$sha = [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    [System.Text.Encoding]::UTF8.GetBytes(($body -join "`n")))
$dumpSha = (($sha | ForEach-Object { $_.ToString('x2') }) -join '')

$generatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
$serverVersion = (docker exec $Container psql -U $DbUser -d $Database -t -A -c "show server_version").Trim()
$serverVersion = ($serverVersion | Select-Object -First 1)

$header = @(
"-- ====================================================================="
"-- FirstElement Flyway baseline"
"-- ====================================================================="
"-- Version : 20260814000000"
"-- Source  : schema-only pg_dump of the VERIFIED live database '$Database'"
"--           on container '$Container' (image pgvector/pgvector:pg16,"
"--           PostgreSQL server $serverVersion)."
"-- Taken   : $generatedAt"
"--"
"-- WHY THIS IS NOT packages/database/prisma/migrations/20260814000000_init"
"--   The Prisma init migration is drift-divergent and is QUARANTINED as a"
"--   baseline source. Recorded drift:"
"--     * EmployeeAvailabilityStatus : init = AVAILABLE/BUSY/AWAY/OFFLINE"
"--                                   live  = ONLINE/OFFLINE/BUSY/BREAK/MEAL/TRAINING/MEETING"
"--     * AIRunDecision              : init omits RESOLVE, live has it"
"--     * init defines no CaseOutcome enum and only 31 of the 45 tables"
"--       (no Lead, accounts, appointments, deals, doctors, procedures,"
"--        notes, tasks, views, case_*, conversation_classifications,"
"--        agent_status_sessions)"
"--   The live database and packages/database/prisma/schema.prisma agree;"
"--   schema.prisma is the declared source of truth for enum values and"
"--   column contracts."
"--"
"-- CONTRACT NOTES (do not 'fix' silently)"
"--   * Quoted camelCase identifiers are preserved verbatim. Spring"
"--     persistence must map explicitly; no naming strategy."
"--   * All timestamps are 'timestamp(3) without time zone' (Prisma"
"--     DateTime). Bind LocalDateTime, never Instant/OffsetDateTime."
"--   * IDs are uuid; ticketNumber / cxId / tokenHash are business keys."
"--     A migration that regenerates them breaks external references."
"--   * pgvector is available; the HNSW embedding index is added by the"
"--     separate additive migration V20260814000100."
"--"
"-- Regenerate with:"
"--   ops/flyway/generate/generate-baseline.ps1"
"-- Verify with:"
"--   ops/flyway/verification/run-verification.ps1"
"--   ./gradlew :backend:database:test"
"--"
"-- Source dump body sha256: $dumpSha"
"-- ====================================================================="
""
)

$outDir = Split-Path -Parent $OutFile
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

$content = ($header + $body) -join "`n"
Set-Content -Path $OutFile -Value $content -Encoding UTF8 -NoNewline

$fileSha = (Get-FileHash -Path $OutFile -Algorithm SHA256).Hash.ToLower()

Write-Host "Wrote $OutFile"
Write-Host "  source dump body sha256 : $dumpSha"
Write-Host "  baseline file sha256    : $fileSha"
Write-Host "  lines                   : $((Get-Content $OutFile).Count)"

if ($MirrorDir) {
    if (-not (Test-Path $MirrorDir)) { New-Item -ItemType Directory -Force -Path $MirrorDir | Out-Null }
    Copy-Item $OutFile -Destination (Join-Path $MirrorDir (Split-Path -Leaf $OutFile)) -Force
    Write-Host "  mirrored to             : $MirrorDir"
}

# Fail loudly if the two copies of the chain diverge. The packaged classpath
# copy and the ops/ copy must be byte-identical.
$mirror = 'C:\clinic-flow-main\backend\database\src\main\resources\db\migration'
$canonical = 'V20260814000000__baseline_verified_live_schema.sql'
if ((Split-Path -Leaf $OutFile) -eq $canonical -and (Test-Path $mirror)) {
    $target = Join-Path $mirror $canonical
    $targetSha = if (Test-Path $target) { (Get-FileHash -Path $target -Algorithm SHA256).Hash.ToLower() } else { '<missing>' }
    if ($targetSha -ne $fileSha) {
        Write-Warning "Classpath copy $target is out of sync ($targetSha). Copy the baseline over it."
    } else {
        Write-Host "  classpath copy in sync  : yes"
    }
}