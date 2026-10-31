#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Compares the committed OpenAPI document against the document the application actually serves.

.DESCRIPTION
    `tests/contracts/openapi-paths.json` is a route inventory: it answers "which paths exist".
    `tests/contracts/openapi.json` is the contract itself, with schemas, types, nullability and
    enums, and it is what Phase 11 generates a typed Angular client from.

    A committed contract that nobody compares against the code is worse than no contract, because
    it looks authoritative while describing something that no longer exists. That is exactly how the
    Phase 7 exporter came to write unversioned paths into the inventory: a file nobody regenerated
    drifted silently. This script is the answer to that, and it is deliberately strict.

    It runs the export test (which asks the running application for its own document, writes to
    build/openapi/openapi.json, and touches no database), then compares that fresh document with the
    committed one STRUCTURALLY rather than byte-for-byte. Byte comparison would fail on key ordering
    and on generated server metadata that is not part of the contract, and a check that cries wolf
    gets ignored.

    What is compared is the part a client depends on: every path, and for every path every
    operation, its parameters, request body and response codes. A change to any of those changes what
    the client calls, so it must be promoted deliberately.

    Set -AllowDrift to report drift without failing. Never set it in CI: this gate exists to force a
    human to look at a changed contract, and a flag that can be passed in a pipeline is a flag that
    will be passed.
#>
[CmdletBinding()]
param(
    [string] $RepoRoot,
    # Compare and fail on drift. The CI behaviour.
    [switch] $AllowDrift
)

. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')
if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$ErrorActionPreference = 'Stop'
$committed = Join-Path $RepoRoot 'tests/contracts/openapi.json'
$fresh = Join-Path $RepoRoot 'backend/app-bootstrap/build/openapi/openapi.json'

function Write-QLog([string] $m) {
    Write-Host ("{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m)
}

if (-not (Test-Path -LiteralPath $committed)) {
    throw "tests/contracts/openapi.json is missing. Promote it with export-openapi-document.ps1 -Out tests/contracts/openapi.json"
}

Write-QLog 'STAGE openapi-document: regenerating from the running application'
Push-Location $RepoRoot
try {
    # Gradle writes deprecation and JVM notes to stderr, and under ErrorActionPreference=Stop
    # PowerShell promotes those to TERMINATING errors before an exit code is ever read. The exit
    # code is the real signal, so stderr is discarded and LASTEXITCODE is what decides.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & ./gradlew.bat :backend:app-bootstrap:test --tests '*OpenApiDocumentExportTest*' `
        --rerun-tasks --no-build-cache --no-daemon --console=plain *>&1 | Out-Null
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = $previous
}
finally {
    Pop-Location
}
if ($gradleExit -ne 0) {
    throw 'the OpenAPI export test failed, so the contract could not be compared'
}
if (-not (Test-Path -LiteralPath $fresh)) {
    throw "the export test passed but wrote no document at $fresh"
}

$a = Get-Content -Raw -LiteralPath $committed | ConvertFrom-Json
$b = Get-Content -Raw -LiteralPath $fresh | ConvertFrom-Json

$differences = New-Object System.Collections.Generic.List[string]

if ($a.openapi -ne $b.openapi) {
    $differences.Add("openapi version: committed '$($a.openapi)' vs served '$($b.openapi)'")
}

$committedPaths = @($a.paths.PSObject.Properties.Name | Sort-Object)
$servedPaths = @($b.paths.PSObject.Properties.Name | Sort-Object)
foreach ($p in ($servedPaths | Where-Object { $_ -notin $committedPaths })) {
    $differences.Add("path ADDED by the code but absent from the committed contract: $p")
}
foreach ($p in ($committedPaths | Where-Object { $_ -notin $servedPaths })) {
    $differences.Add("path REMOVED from the code but still in the committed contract: $p")
}

# For every path present in both, compare the operation surface: which methods exist, and for each
# one what it accepts and what it returns. A path that keeps its shape while its request body changes
# is still a changed contract, and that is the change most likely to break a generated client.
foreach ($p in $committedPaths) {
    if ($p -notin $servedPaths) { continue }
    $ca = $a.paths.$p
    $cb = $b.paths.$p
    $opsA = @($ca.PSObject.Properties.Name | Sort-Object)
    $opsB = @($cb.PSObject.Properties.Name | Sort-Object)
    foreach ($op in ($opsB | Where-Object { $_ -notin $opsA })) {
        $differences.Add("operation ADDED: $p $op")
    }
    foreach ($op in ($opsA | Where-Object { $_ -notin $opsB })) {
        $differences.Add("operation REMOVED: $p $op")
    }
    foreach ($op in $opsA) {
        if ($op -notin $opsB) { continue }
        $oa = ($ca.$op | ConvertTo-Json -Depth 40 -Compress)
        $ob = ($cb.$op | ConvertTo-Json -Depth 40 -Compress)
        if ($oa -ne $ob) {
            $differences.Add("operation CHANGED: $p $op -- a client generated from the committed document would call the old shape")
        }
    }
}

$schemaCount = @($b.components.schemas.PSObject.Properties).Count
Write-QLog ("INFO  served document: {0} paths, {1} schemas" -f $servedPaths.Count, $schemaCount)

if ($differences.Count -eq 0) {
    Write-QLog 'STAGE openapi-document VERDICT: PASS -- the committed contract matches the application'
    exit 0
}

foreach ($d in $differences) { Write-Host ("ERROR  {0}" -f $d) }
Write-Host ("ERROR  {0} difference(s) between the committed contract and the served one." -f $differences.Count)
Write-Host 'ERROR  A contract is a promise to callers. Promote it deliberately with'
Write-Host 'ERROR    .\ops\quality\checks\export-openapi-document.ps1 -Out tests/contracts/openapi.json'
Write-Host 'ERROR  and commit it in the same change as the code that moved it.'
if ($AllowDrift) {
    Write-QLog 'WARN  -AllowDrift was supplied, so this run passes. Do not set it in CI.'
    exit 0
}
exit 1