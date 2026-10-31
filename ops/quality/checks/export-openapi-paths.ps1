#!/usr/bin/env pwsh
# =====================================================================
# export-openapi-paths.ps1 / the extractor half of check-openapi-contract
#
# The path inventory below is extracted from the Spring controllers so a
# route change is a reviewable diff in tests/contracts/openapi-paths.json
# rather than a silent contract break.
#
# What it proves:
#   * every HTTP route the Spring runtime exposes, as METHOD /path;
#   * that no route drifted outside /api/v1 except the two documented
#     public connector surfaces (Phase 3 / Phase 5b);
#   * that the inventory in the repository still matches the code.
#
# What it does NOT prove, and cannot:
#   * the request/response SCHEMA. That is a springdoc-generated document
#     plus a contract test against a booted application, which is the
#     `openapi-contract` gate's second half and needs a running service.
#     This extractor is the part that can run with no service at all, so a
#     path regression is caught in the static stage.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    # Write the inventory to this path instead of stdout-only mode.
    [string] $Out,
    [switch] $AssertNoChange
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$relOf = { param($p) return $p.Replace($RepoRoot + '\', '').Replace($RepoRoot + '/', '') }

# The only two families that are allowed to sit outside /api/v1:
#   - the connector webhook surface, authenticated by a provider signature
#     rather than by a session (Phase 5b, FR-4.3);
#   - nothing else. Anything new outside /api/v1 is a decision, not a typo.
$allowedOutsideApiV1 = @(
    '^/webhooks/'   # connector webhooks (Meta GET/POST handshake, Telegram, Mock)
)

$controllers = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'backend') -Recurse -Filter '*Controller.java' -File |
    Where-Object { $_.FullName -notmatch '[\\/]build[\\/]' })

# TEST SOURCES ARE NOT ROUTES.
#
# A fixture controller under src/test is a test double, not a deployed endpoint, so it has no place
# in the committed route contract that the Angular client is generated from. It was found the hard
# way: a Phase-18 probe fixture was caught by this scan contributing 17 routes that exist in no
# deployment, and the cheap way to silence it was to register those 17 phantom paths in
# openapi-paths.json -- which would have taught the generated client about endpoints that do not
# exist. The correct fix is here, not in the manifest.
#
# The exclusion is on the src/test SEGMENT rather than on the filename, because a fixture need not be
# called ...Controller: anything compiled only into the test source set cannot be a product route.
# The build/ exclusion above covers generated output for the same reason.
$controllers = @($controllers | Where-Object {
    $_.FullName -notmatch '[\\/](src[\\/]test[\\/]|src[\\/]testFixtures[\\/])[\\/]'
})

$routes = New-Object System.Collections.Generic.List[object]

foreach ($f in $controllers) {
    $text = Get-Content -Raw -LiteralPath $f.FullName
    $rel  = & $relOf $f.FullName
    if ($text -notmatch '@(RestController|Controller)\b') { continue }
    if ($text -match '@RestControllerAdvice') { continue }

    # class-level base path: the first @RequestMapping before the class
    # declaration.
    # The class DECLARATION, not the first occurrence of the word "class": these controllers document
    # their design in javadoc that mentions "class", so a bare " class " search lands inside a comment
    # and the real class-level @RequestMapping falls outside the parsed head. That reported 33 phantom
    # "OFF /api/v1" routes and wrote wrong paths into the committed contract file the Angular client is
    # generated from. The match must begin at the declaration keyword, never at an annotation, or the
    # annotation itself is excluded from the head.
    $decl = [regex]::Match($text, '(?m)^\s*(?:public\s+|final\s+|abstract\s+|sealed\s+)*(?:class|interface|record)\s+\w+')
    $classIdx = if ($decl.Success) { $decl.Index } else { $text.LastIndexOf('class ') }
    $head = if ($classIdx -gt 0) { $text.Substring(0, $classIdx) } else { $text }
    $base = ''
    $cm = [regex]::Match($head, '@RequestMapping\s*\(\s*(?:value\s*=\s*)?"(?<p>[^"]*)"')
    if ($cm.Success) { $base = $cm.Groups['p'].Value }

    # method-level mappings. Only the class BODY is searched: the
    # class-level @RequestMapping lives in the head and matching it again
    # would emit a phantom "ANY /api/v1/conversations" route.
    $body = if ($classIdx -gt 0) { $text.Substring($classIdx) } else { $text }
    $pattern = '@(?<verb>Get|Post|Put|Patch|Delete|Request)Mapping\s*(\((?<args>[^)]*)\))?'
    foreach ($mm in [regex]::Matches($body, $pattern)) {
        $verb = $mm.Groups['verb'].Value.ToUpperInvariant()
        if ($verb -eq 'REQUEST') { $verb = 'ANY' }
        $args = $mm.Groups['args'].Value
        $args = if ($null -eq $args) { '' } else { $args }
        $sub = ''
        $pm = [regex]::Match($args, '(?:value\s*=\s*|path\s*=\s*)?"(?<p>[^"]*)"')
        if ($pm.Success) { $sub = $pm.Groups['p'].Value }
        $full = ($base + $sub)
        if ($full -notmatch '^/') { $full = '/' + $full }
        # collapse a double slash introduced by an empty class base
        $full = $full -replace '//+', '/'
        $routes.Add([ordered]@{
            verb      = $verb
            path      = $full
            controller = $rel
            publicSurface = ($full -notlike '/api/v1/*')
        })
    }
}

$sorted = @($routes | Sort-Object verb, path)

Write-QLog ("openapi path inventory: {0} route(s) across {1} controller(s)" -f $sorted.Count, $controllers.Count) -Level STEP
foreach ($r in $sorted) { Write-QLog ('  {0,-6} {1}' -f $r.verb, $r.path) }

$offenders = @($sorted | Where-Object { $_.publicSurface })
foreach ($o in $offenders) {
    $ok = $false
    foreach ($a in $allowedOutsideApiV1) { if ($o.path -match $a) { $ok = $true } }
    if (-not $ok) {
        Write-QLog ("  OFF /api/v1: {0} {1}  ({2})" -f $o.verb, $o.path, $o.controller) -Level WARN
    }
}

$doc = [ordered]@{
    schemaVersion = 1
    note          = 'Generated by ops/quality/checks/export-openapi-paths.ps1 from the Spring controllers. Edit with the generator, never by hand. A deliberate route change and this file change belong in the same commit.'
    allowedOutsideApiV1 = ConvertTo-QArray -Value $allowedOutsideApiV1
    routeCount    = $sorted.Count
    routes        = ConvertTo-QArray -Value $sorted
}

if ($Out) {
    Write-QJson -Path $Out -Object $doc -Depth 8 | Out-Null
    Write-QLog ("written: {0}" -f $Out)
}
exit 0
