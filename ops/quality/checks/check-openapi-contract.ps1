#!/usr/bin/env pwsh
# =====================================================================
# check-openapi-contract.ps1
#
# The static half of the contract gate: the route inventory the Spring
# controllers declare must equal the inventory committed in
# tests/contracts/openapi-paths.json, and every route must live under
# /api/v1 except the documented public connector webhook surface.
#
# A route that changes without the inventory changing is a contract break
# that no reviewer would notice in a diff of 200 Java files. This check
# makes the diff mandatory and therefore reviewable.
#
# The DYNAMIC half -- the request/response schema, from springdoc, against
# a booted application -- needs a running service and is the
# `openapi-contract` stage's second command. Phase 3 recorded "No
# OpenAPI contract test pins the new endpoints' shapes" and Phase 4
# recorded the same; this stage is where that debt is closed once an
# application is bootable in CI. Until then this half runs and the other
# half is staged, visibly.
#
# Exit codes: 0 match, 1 drift, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $Inventory,
    [string] $JsonOut,
    # Print what changed instead of writing the inventory.
    [switch] $Diff
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $Inventory) { $Inventory = Join-Path $RepoRoot 'tests\contracts\openapi-paths.json' }

$problems = New-Object System.Collections.Generic.List[string]

if (-not (Test-Path -LiteralPath $Inventory)) {
    Write-QLog ("route inventory missing: {0}" -f $Inventory) -Level ERROR
    Write-QLog 'Regenerate it with: .\ops\quality\checks\export-openapi-paths.ps1 -Out .\tests\contracts\openapi-paths.json' -Level ERROR
    exit 2
}

$committed = Get-Content -Raw -LiteralPath $Inventory | ConvertFrom-Json

# Re-extract into a scratch file so the two inventories are produced by the
# same code path.
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('fe-openapi-' + (Get-QUtcTimestamp) + '.json')
& (Join-Path $PSScriptRoot 'export-openapi-paths.ps1') -RepoRoot $RepoRoot -Out $scratch | Out-Null
if ($LASTEXITCODE -ne 0) { Write-QLog 'route extraction failed' -Level ERROR; exit 1 }
$actual = Get-Content -Raw -LiteralPath $scratch | ConvertFrom-Json
Remove-Item -LiteralPath $scratch -Force -ErrorAction SilentlyContinue

$key = { param($r) ([string]$r.verb) + ' ' + ([string]$r.path) }

$committedKeys = @{}
foreach ($r in @($committed.routes)) { $committedKeys[(& $key $r)] = $r }
$actualKeys = @{}
foreach ($r in @($actual.routes)) { $actualKeys[(& $key $r)] = $r }

$added = @()
$removed = @()
foreach ($k in $actualKeys.Keys) { if (-not $committedKeys.ContainsKey($k)) { $added += $k } }
foreach ($k in $committedKeys.Keys) { if (-not $actualKeys.ContainsKey($k)) { $removed += $k } }

Write-QLog ("openapi contract: {0} committed route(s), {1} in code" -f $committedKeys.Count, $actualKeys.Count) -Level STEP

foreach ($k in ($added | Sort-Object)) {
    $problems.Add(("ROUTE ADDED without a contract change: {0}   [{1}]" -f $k, $actualKeys[$k].controller))
}
foreach ($k in ($removed | Sort-Object)) {
    $problems.Add(("ROUTE REMOVED without a contract change: {0}   [{1}]" -f $k, $committedKeys[$k].controller))
}

# Every route must be under /api/v1 except the allow-list the committed
# document carries (so changing the allow-list is itself reviewable).
$allowed = @()
if ($committed.PSObject.Properties.Name -contains 'allowedOutsideApiV1') { $allowed = @($committed.allowedOutsideApiV1) }
foreach ($r in @($actual.routes)) {
    if ($r.path -like '/api/v1/*') { continue }
    $ok = $false
    foreach ($a in $allowed) { if ([string]$r.path -match [string]$a) { $ok = $true } }
    if (-not $ok) {
        $problems.Add(("ROUTE OUTSIDE /api/v1 and not allow-listed: {0} {1}   [{2}]" -f $r.verb, $r.path, $r.controller))
    }
}

# A path that repeats a literal segment is the signature of a method-level
# annotation carrying an ABSOLUTE path under a class-level @RequestMapping
# (`/api/v1/conversations` + `/api/v1/conversations`), which routes
# nowhere and is invisible in review. A literal before a template is fine
# and common (`/users/{userId}/role`, `/permissions/me`), so it is NOT
# flagged.
foreach ($r in @($actual.routes)) {
    $p = [string]$r.path
    $parts = @($p -split '/' | Where-Object { $_ -ne '' -and $_ -notmatch '^\{' })
    $seen = @{}
    $dupes = @()
    foreach ($seg in $parts) { if ($seen.ContainsKey($seg)) { $dupes += $seg } else { $seen[$seg] = $true } }
    if ($dupes.Count -gt 0) {
        $problems.Add(("ROUTE REPEATS A LITERAL SEGMENT ({0}): {1} {2}   [{3}]" -f (($dupes | Select-Object -Unique) -join ','), $r.verb, $p, $r.controller))
    }
}

$verdict = if ($problems.Count -eq 0) { 'PASS' } else { 'FAIL' }

if ($Diff -and $verdict -eq 'FAIL') {
    Write-QLog ''
    Write-QLog 'route inventory drift:' -Level ERROR
    foreach ($p in $problems) { Write-QLog ('  - ' + $p) -Level ERROR }
}

$result = [ordered]@{
    check          = 'openapi-contract'
    verdict        = $verdict
    checkedAtUtc   = (Get-Date).ToUniversalTime().ToString('o')
    inventory      = $Inventory
    committedCount = $committedKeys.Count
    actualCount    = $actualKeys.Count
    added          = ConvertTo-QArray -Value $added
    removed        = ConvertTo-QArray -Value $removed
    allowedOutsideApiV1 = ConvertTo-QArray -Value $allowed
    problems       = ConvertTo-QArray -Value $problems
    scope          = 'route inventory only; request/response schema requires a booted application and is the second command of the openapi-contract stage'
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

Write-QLog ("  routes added   : {0}" -f $added.Count)
Write-QLog ("  routes removed : {0}" -f $removed.Count)

if ($verdict -eq 'FAIL') {
    foreach ($p in $problems) { Write-QLog ('  - ' + $p) -Level ERROR }
    Write-QLog 'openapi-contract VERDICT: FAIL' -Level ERROR
    Write-QLog ('If the change is intended: .\ops\quality\checks\export-openapi-paths.ps1 -Out .\tests\contracts\openapi-paths.json') -Level ERROR
    exit 1
}
Write-QLog 'openapi-contract VERDICT: PASS -- the committed route inventory matches the controllers' -Level STEP
exit 0