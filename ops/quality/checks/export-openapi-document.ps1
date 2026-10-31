#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Promotes the application's live OpenAPI document into tests/contracts/openapi.json.

.DESCRIPTION
    Promotion is a deliberate act, never a side effect of a test run. This script asks the running
    application for the document it actually serves, writes it to -Out, and prints what changed
    relative to the currently committed copy so the commit message can say what moved.

    Use it in the SAME commit as the code that changed the contract. A contract change shipped
    without the document tells every existing client that nothing happened.

.PARAMETER Out
    Where to write the promoted document. Defaults to tests/contracts/openapi.json.
#>
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $Out = 'tests/contracts/openapi.json'
)

. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')
if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$ErrorActionPreference = 'Stop'
$target = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $RepoRoot $Out }
$fresh = Join-Path $RepoRoot 'backend/app-bootstrap/build/openapi/openapi.json'

function Write-QLog([string] $m) {
    Write-Host ("{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m)
}

Push-Location $RepoRoot
try {
    Write-QLog 'STAGE export-openapi-document: asking the application for its own contract'
    # See check-openapi-document.ps1: Gradle's stderr notes must not become terminating errors.
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
    throw 'the OpenAPI export test failed; there is nothing to promote'
}
if (-not (Test-Path -LiteralPath $fresh)) {
    throw "the export test passed but wrote no document at $fresh"
}

$before = $null
if (Test-Path -LiteralPath $target) {
    $committed = Get-Content -Raw -LiteralPath $target | ConvertFrom-Json
    $served = Get-Content -Raw -LiteralPath $fresh | ConvertFrom-Json
    $before = @($committed.paths.PSObject.Properties.Name | Sort-Object)
    $after = @($served.paths.PSObject.Properties.Name | Sort-Object)
    $added = @($after | Where-Object { $_ -notin $before })
    $removed = @($before | Where-Object { $_ -notin $after })
    Write-QLog ("INFO  paths before={0} after={1}" -f $before.Count, $after.Count)
    foreach ($p in $added) { Write-Host ("INFO    + {0}" -f $p) }
    foreach ($p in $removed) { Write-Host ("INFO    - {0}" -f $p) }
}

Copy-Item -LiteralPath $fresh -Destination $target -Force
$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
Write-QLog ("INFO  promoted {0} ({1} bytes)" -f $target, (Get-Item -LiteralPath $target).Length)
Write-QLog ("INFO  sha256: {0}" -f $hash)
Write-QLog 'INFO  Commit this together with the code that changed the contract.'