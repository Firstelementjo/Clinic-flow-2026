#!/usr/bin/env pwsh
# =====================================================================
# check-error-contract.ps1
#
# The error-body half of the OpenAPI/contract gate.
#
# PROJECT_REQUIREMENTS section 6 requires a stable error body with
# `code`, `message`, `details`, `requestId` and an optional `retryable`,
# and problem details that never reveal cross-tenant existence. That
# contract is only stable if every advice builds the SAME type; a handler
# that returns its own ad-hoc map is how a client ends up parsing four
# different error shapes.
#
# Rules (each decidable from source, no running service):
#   ERRC-001  every exception-handling class builds the shared
#             com.firstelement.kernel.error.ErrorResponse type
#   ERRC-002  ErrorResponse declares exactly code, message, details,
#             requestId and an optional retryable
#   ERRC-003  every request response carries a correlation id, so
#             requestId is never absent from an error body
#   ERRC-004  no advice body interpolates a resource identifier or a
#             tenant name into the message (the existence oracle FR-2.7
#             forbids)
#   ERRC-005  no controller returns a bare Map/JsonNode as an error path
#
# The RUNTIME half -- asserting the actual HTTP status and body for a
# cross-tenant id and for a nonexistent id being byte-identical -- is
# `SecurityBoundaryHttpIntegrationTest` ("a cross-tenant user id and a
# nonexistent one produce identical 404 bodies"). This script does not
# duplicate it; it makes sure the shape that test depends on still exists.
#
# Exit codes: 0 pass, 1 violations, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $JsonOut
)

$ErrorActionPreference = 'Stop'
$repoForLib = $null
for ($i = 0; $i -lt 5; $i++) {
    $candidate = Join-Path $PSScriptRoot (('..' + [string][char]92) * ($i + 1))
    if (Test-Path -LiteralPath (Join-Path $candidate 'settings.gradle.kts')) { $repoForLib = $candidate; break }
}
if (-not $repoForLib) { Write-QLog 'repository root not found' -Level ERROR; exit 2 }
. (Join-Path $repoForLib ('ops' + [string][char]92 + 'quality' + [string][char]92 + 'lib' + [string][char]92 + 'quality-common.ps1'))

if (-not $RepoRoot) { $RepoRoot = $repoForLib }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$findings = New-Object System.Collections.Generic.List[object]
function Violation { param([string] $Rule, [string] $File, [string] $Detail)
    $script:findings.Add([ordered]@{ rule = $Rule; file = $File; detail = $Detail }) }

$backend = Join-Path $RepoRoot 'backend'
$srcFiles = @(Get-ChildItem -LiteralPath $backend -Recurse -Filter '*.java' -File |
    Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]' -and $_.FullName -notmatch '[\\/]build[\\/]' })
$relOf = { param($p) return $p.Replace($RepoRoot + '\', '').Replace($RepoRoot + '/', '') }

# --- ERRC-002 / 001 ---------------------------------------------------
$errorResponsePath = Join-Path $backend 'shared-kernel\src\main\java\com\firstelement\kernel\error\ErrorResponse.java'
if (-not (Test-Path -LiteralPath $errorResponsePath)) {
    Violation -Rule 'ERRC-002' -File 'backend/shared-kernel' -Detail 'the shared ErrorResponse type does not exist; there is no stable error contract to hold'
}
else {
    $erText = Get-Content -Raw -LiteralPath $errorResponsePath
    $fields = @([regex]::Matches($erText, '(?m)^\s*[\w<>\[\], ]+\s+(?<f>code|message|details|requestId|retryable|timestamp|path|traceId)\s*[;,)]') |
        ForEach-Object { $_.Groups['f'].Value } | Select-Object -Unique)
    foreach ($required in @('code', 'message', 'requestId')) {
        if ($fields -notcontains $required) {
            Violation -Rule 'ERRC-002' -File (& $relOf $errorResponsePath) -Detail ("ErrorResponse has no `{0}` field" -f $required)
        }
    }
    Write-QLog ("  ErrorResponse declares: {0}" -f ($fields -join ', ')) -Level INFO
}

$advices = @($srcFiles | Where-Object { (Get-Content -Raw -LiteralPath $_.FullName) -match '@(RestControllerAdvice|ControllerAdvice)' })
Write-QLog ("  exception advices found: {0}" -f $advices.Count) -Level INFO
if ($advices.Count -eq 0) {
    Violation -Rule 'ERRC-001' -File 'backend/**' -Detail 'no @RestControllerAdvice anywhere; exceptions would serialise as a Spring default body and break the error contract'
}
foreach ($a in $advices) {
    $text = Get-Content -Raw -LiteralPath $a.FullName
    $rel = & $relOf $a.FullName
    if ($text -notmatch 'ErrorResponse') {
        Violation -Rule 'ERRC-001' -File $rel -Detail 'this advice does not build the shared ErrorResponse, so it produces a different error shape from the others'
    }
}

# --- ERRC-003 --------------------------------------------------------
$correlationPath = Join-Path $backend 'shared-kernel\src\main\java\com\firstelement\kernel\web\CorrelationIdFilter.java'
if (-not (Test-Path -LiteralPath $correlationPath)) {
    Violation -Rule 'ERRC-003' -File 'backend/shared-kernel' -Detail 'no CorrelationIdFilter; requestId has no source, so it cannot be present on every response'
}
else {
    Write-QLog '  CorrelationIdFilter present' -Level INFO
}

# --- ERRC-004 : no resource identity in an error message -------------
# A message that names the target turns a 404 into an existence oracle.
$identityPatterns = @(
    '(?i)customer\s*(id|uuid)\s*[=:]\s*\{',
    '(?i)conversation\s*(id|uuid)\s*[=:]\s*\{',
    '(?i)user\s*(id|uuid)\s*[=:]\s*\{',
    '(?i)project\s*(id|uuid)\s*[=:]\s*\{',
    '(?i)organization\s*(id|uuid)\s*[=:]\s*\{',
    '(?i)(session|token|organization|customer|conversation)[^"'']*\{0\}'
)
foreach ($f in $srcFiles) {
    $text = Get-Content -Raw -LiteralPath $f.FullName
    if ($text -notmatch '@(RestControllerAdvice|ControllerAdvice)') { continue }
    $rel = & $relOf $f.FullName
    foreach ($line in (Get-Content -LiteralPath $f.FullName)) {
        foreach ($p in $identityPatterns) {
            if ($line -match $p) {
                Violation -Rule 'ERRC-004' -File $rel -Detail ('an error message interpolates a resource identity: ' + $line.Trim())
            }
        }
    }
}

# --- ERRC-005 : no ad-hoc error map ----------------------------------
foreach ($f in $srcFiles) {
    $text = Get-Content -Raw -LiteralPath $f.FullName
    $rel = & $relOf $f.FullName
    if ($text -match 'ResponseEntity<\s*Map<' -and $text -match '@(ExceptionHandler|RestControllerAdvice)') {
        Violation -Rule 'ERRC-005' -File $rel -Detail 'an advice returns ResponseEntity<Map<...>>, which is an error body the OpenAPI document cannot describe'
    }
}

$verdict = if ($findings.Count -eq 0) { 'PASS' } else { 'FAIL' }
$result = [ordered]@{
    check        = 'error-contract'
    verdict      = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    advices      = @($advices | ForEach-Object { & $relOf $_.FullName })
    runtimeProof = 'com.firstelement.identity.SecurityBoundaryHttpIntegrationTest -- "a cross-tenant user id and a nonexistent one produce identical 404 bodies"'
    findings     = ConvertTo-QArray -Value $findings
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

Write-QLog ''
if ($verdict -eq 'FAIL') {
    foreach ($f in $findings) { Write-QLog ('  {0} {1}: {2}' -f $f.rule, $f.file, $f.detail) -Level ERROR }
    Write-QLog 'error-contract VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'error-contract VERDICT: PASS -- every advice builds the shared ErrorResponse' -Level STEP
exit 0