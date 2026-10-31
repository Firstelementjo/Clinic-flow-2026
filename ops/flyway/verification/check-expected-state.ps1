#!/usr/bin/env pwsh
# Validates that ops/flyway/expected-state.json still parses, and prints the
# profile the quality gate reads. Run after ANY edit to that file: the gate
# (ops/quality/checks/verify-schema-state.ps1) FAILS CLOSED when neither
# profile matches, so a malformed file takes the whole schema gate down with a
# parse error that says nothing about which number is wrong.
[CmdletBinding()]
param([string] $Path = 'ops/flyway/expected-state.json')

$ErrorActionPreference = 'Stop'
try {
    $j = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
} catch {
    Write-Host "INVALID: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
$post = $j.profiles.postBoot.alsoMeasured
Write-Host 'JSON VALID'
Write-Host ("  chain      : lastVersion {0}, migrationCount {1}" -f $j.chain.lastVersion, $j.chain.migrationCount)
Write-Host ("  preBoot    : tables {0}, enums {1}, checks {2}, fingerprint {3}" -f `
    $j.profiles.preBoot.tableCount, $j.profiles.preBoot.enumCount,
    $j.profiles.preBoot.checkCount, $j.profiles.preBoot.fingerprint)
Write-Host ("  postBoot   : tables {0}, enums {1}, checks {2}, fingerprint {3}" -f `
    $j.profiles.postBoot.tableCount, $j.profiles.postBoot.enumCount,
    $j.profiles.postBoot.checkCount, $j.profiles.postBoot.fingerprint)
Write-Host ("  alsoMeasured: FKs {0}, PKs {1}, indexes {2}, uniqueIdx {3}, uniqConstr {4}, triggers {5}, nullableProjectId {6}" -f `
    $post.foreignKeyCount, $post.primaryKeyCount, $post.indexCount,
    $post.uniqueIndexCount, $post.uniqueConstraintCount,
    $post.triggerCount, $post.nullableProjectIdCount)
exit 0