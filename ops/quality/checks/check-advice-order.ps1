#!/usr/bin/env pwsh
# =====================================================================
# check-advice-order.ps1
#
# Enforces that the @RestControllerAdvice ladder is declared rather
# than discovered, because one entry in that ladder decides whether a
# cross-tenant denial is answered as a non-disclosing 404 or as a 500.
#
# WHY A STATIC GATE
#   Spring resolves an @ExceptionHandler by walking the advice beans
#   IN ORDER and stopping at the first advice that has a handler for
#   the exception that actually escaped the handler method. Two advices
#   with no @Order both default to Ordered.LOWEST_PRECEDENCE, tie, and
#   the winner is then decided by component-scan registration order --
#   which is, in practice, the line order of a module list in one
#   build.gradle.kts. Changing that build file silently changes which
#   advice answers, which is how a phase-11 refusal became a 500.
#
#   A handler that is registered but shadowed is worse than one that
#   is absent, because it reads as fixed. So the order is asserted here
#   rather than trusted, and a 4th advice added without an @Order fails
#   the build rather than being discovered by a production incident.
#
# Rules that FAIL the build:
#   FE-ADVICE-001  an advice declares no @Order, so its position is
#                  decided by component-scan registration order
#   FE-ADVICE-002  two advices declare the same order, so the winner
#                  between them is decided by component scan
#   FE-ADVICE-003  an advice other than the floor declares
#                  LOWEST_PRECEDENCE, which ties it with the
#                  @ExceptionHandler(Exception.class) catch-all
#   FE-ADVICE-004  the ladder has not exactly one floor: zero floors
#                  means an unclaimed exception leaves the dispatcher
#                  as a container default, and two floors means two
#                  catch-alls competing for every exception
#   FE-ADVICE-006  an @Order expression this script cannot resolve to
#                  an int, so the declared position is unverifiable
#
# Rule that is RECORDED (advisory, does not fail):
#   FE-ADVICE-005  an advice narrows its scope to a package, an
#                  annotation or a controller type. Legitimate for a
#                  module that owns one surface; a latent trap for the
#                  security family, where a controller outside the
#                  namespace turns a denial into a 500.
#
# SCOPE OF THE SCAN
#   Production sources under backend/*/src/main/java by default. A
#   test-source advice orders itself only within one test context, and
#   two fixtures in different modules having the same order is not a
#   conflict. Pass -IncludeTestSources to audit fixtures too.
#
# Exit codes: 0 clean, 1 violations, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $JsonOut,
    # Also scan src/test/java. Advisory fixtures are excluded by
    # default because advice order is only meaningful inside one
    # assembled application.
    [switch] $IncludeTestSources
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$backendRoot = Join-Path $RepoRoot 'backend'
if (-not (Test-Path -LiteralPath $backendRoot)) {
    Write-QLog 'backend/ not found; nothing to check.' -Level ERROR
    exit 2
}

$skipDirs = '[\\/]build[\\/]'
$javaFiles = @(Get-ChildItem -LiteralPath $backendRoot -Recurse -Filter '*.java' -File |
    Where-Object { $_.FullName -notmatch $skipDirs })
$scanned = @($javaFiles | Where-Object {
    if ($_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]') { return $true }
    if ($IncludeTestSources -and $_.FullName -match '[\\/]src[\\/]test[\\/]java[\\/]') { return $true }
    return $false
})

# Cheap pre-filter before any comment stripping: the overwhelming majority of
# Java sources declare no advice at all, and blanking comments in 464 files to
# find four annotations is the slowest thing a fast gate can do.
$candidates = @($scanned | Where-Object {
    (Get-Content -Raw -LiteralPath $_.FullName) -match '@RestControllerAdvice'
})
Write-QLog ("advice-order gate: {0} Java source(s) in scope, {1} mention an advice" -f $scanned.Count, $candidates.Count) -Level STEP
$totalSources = $scanned.Count
$scanned = $candidates

$relOf = {
    param($f) return $f.Replace($RepoRoot + '\', '').Replace($RepoRoot + '/', '')
}

$findings = New-Object System.Collections.Generic.List[object]
$advices  = New-Object System.Collections.Generic.List[object]

# Raw text of every in-scope source, read once. Both passes below need it and
# this repository has 460-odd Java files.
$rawByPath = @{}
foreach ($f in $scanned) { $rawByPath[$f.FullName] = Get-Content -Raw -LiteralPath $f.FullName }

function Add-Finding {
    param([string] $Rule, [string] $Severity, [string] $File, [string] $Detail)
    $script:findings.Add([ordered]@{
        rule = $Rule; severity = $Severity; file = $File; detail = $Detail
    })
}

function Remove-JavaComments {
    <#
      .SYNOPSIS
      Blank out comments while preserving every character offset.

      .DESCRIPTION
      Not cosmetic. This repository's advices DOCUMENT themselves in prose that
      quotes the annotations they declare -- SecurityExceptionAdvice's javadoc
      names @RestControllerAdvice and basePackages in the paragraph explaining
      why the scope was widened, and ErrorHandlingAdvice's names all three
      orders -- and a scan that reads javadoc as code reports phantom advices
      and phantom duplicates. A gate that reports the prose would be reported
      against by anyone who read the file, and then ignored.

      Spaces replace the comment characters, so every offset into the returned
      string still lines up with the original file. Newlines inside block
      comments are kept, so line numbers in a finding are the file's own.

      This is a regex pass rather than a character scanner because it runs over
      every Java source in the module and a per-character loop in PowerShell
      turns a two-second gate into a ninety-second one.
    #>
    param([string] $Text)

    $result = [regex]::Replace($Text, '(?s)/\*.*?\*/', {
        param($m)
        # Keep the newlines; blank everything else.
        [regex]::Replace($m.Value, '[^\r\n]', ' ')
    })
    $result = [regex]::Replace($result, '(?m)//[^\r\n]*', {
        param($m) [regex]::Replace($m.Value, '[^\r\n]', ' ')
    })
    return $result
}



# ---------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------

# Ordered's two anchors. HIGHEST_PRECEDENCE is Integer.MIN_VALUE and
# LOWEST_PRECEDENCE is Integer.MAX_VALUE; they are spelled out here so
# the script depends on nothing but a text scan.
$ORDERED_HIGHEST = [int]::MinValue
$ORDERED_LOWEST  = [int]::MaxValue

# Every class in scope, by simple name, so an @Order expression that
# names a constant (CrmExceptionAdvice.ORDER) can be resolved without
# compiling anything.
$classIndex = @{}
foreach ($f in $scanned) {
    # Comments blanked here too, for the same reason: a javadoc that quotes
    # `static final int ORDER = ...` would otherwise be able to satisfy a
    # constant lookup the code never declared.
    $text = Remove-JavaComments -Text $rawByPath[$f.FullName]
    $rel = & $relOf $f.FullName
    foreach ($cm in [regex]::Matches($text, '(?m)^\s*(?:public\s+|final\s+|abstract\s+|static\s+)*class\s+(?<n>[A-Za-z_][A-Za-z0-9_]*)')) {
        $name = $cm.Groups['n'].Value
        if (-not $classIndex.ContainsKey($name)) { $classIndex[$name] = $rel }
        $classIndex["$name|$rel"] = $text
    }
}

function Get-IntConstant {
    <#
      .SYNOPSIS
      Resolve a `static final int` from a class's source text.
      Returns $null when it is not found or is not an int literal.
    #>
    param([string] $ClassName, [string] $FieldName, [hashtable] $Seen)

    if (-not $Seen.ContainsKey($ClassName)) { $Seen[$ClassName] = 0 }
    if ($Seen[$ClassName] -gt 8) { return $null }   # a cycle, not a constant
    $Seen[$ClassName] = $Seen[$ClassName] + 1

    $pattern = '(?m)^\s*(?:public\s+|private\s+|protected\s+|static\s+|final\s+)*int\s+' +
               [regex]::Escape($FieldName) + '\s*=\s*(?<v>[^;]+);'
    $best = $null
    foreach ($key in @("$ClassName|$($classIndex[$ClassName])", $ClassName)) {
        $body = $null
        if ($classIndex.ContainsKey($key)) { $body = $classIndex[$key] }
        if (-not $body) { continue }
        foreach ($m in [regex]::Matches($body, $pattern)) {
            $value = Resolve-OrderExpression -Expression $m.Groups['v'].Value.Trim() -Seen $Seen
            if ($null -ne $value) { $best = $value; break }
        }
        if ($null -ne $best) { break }
    }
    return $best
}

function Resolve-OrderExpression {
    <#
      .SYNOPSIS
      Evaluate the small arithmetic subset an @Order argument is written
      in: integer literals, Ordered.HIGHEST_PRECEDENCE /
      Ordered.LOWEST_PRECEDENCE, a ClassName.FIELD constant, joined by
      + and - and optionally parenthesised.

      .DESCRIPTION
      Anything outside that subset returns $null rather than a guess.
      A gate that cannot read the declared order must say so (FE-ADVICE-006)
      instead of asserting a position it never verified.
    #>
    param([string] $Expression, [hashtable] $Seen = @{})

    if (-not $Expression) { return $null }
    $expr = $Expression.Trim()

    # A single parenthesised expression.
    while ($expr.StartsWith('(') -and $expr.EndsWith(')') -and
           ([regex]::Matches($expr, '\(')).Count -eq ([regex]::Matches($expr, '\)')).Count) {
        $expr = $expr.Substring(1, $expr.Length - 2).Trim()
    }

    if ($expr -match '^(?<a>.+?)(?<op>[+-])(?<b>[^+\-]+)$') {
        $left = Resolve-OrderExpression -Expression $Matches['a'] -Seen $Seen
        $right = Resolve-OrderExpression -Expression $Matches['b'] -Seen $Seen
        if ($null -eq $left -or $null -eq $right) { return $null }
        if ($Matches['op'] -eq '+') { return [int] $left + [int] $right }
        return [int] $left - [int] $right
    }

    if ($expr -match '^-?\d+$') { return [int] $expr }
    if ($expr -eq 'Ordered.HIGHEST_PRECEDENCE') { return $ORDERED_HIGHEST }
    if ($expr -eq 'Ordered.LOWEST_PRECEDENCE') { return $ORDERED_LOWEST }

    if ($expr -match '^(?:Ordered\.)?(?<cls>[A-Za-z_][A-Za-z0-9_]*)\.(?<f>[A-Za-z_][A-Za-z0-9_]*)$') {
        return Get-IntConstant -ClassName $Matches['cls'] -FieldName $Matches['f'] -Seen $Seen
    }

    return $null
}

# ---------------------------------------------------------------------
# Advice inventory
# ---------------------------------------------------------------------

# @RestControllerAdvice is legal with or without an argument list, and
# the @Order is legal above it or below it. So the advice match is found
# first, then @Order is looked for on both sides of it, and the class
# name is the first declaration that follows.
foreach ($f in $scanned) {
    # Comments are blanked rather than removed, so every offset below still
    # lines up with the file on disk.
    $text = Remove-JavaComments -Text $rawByPath[$f.FullName]
    $rel = & $relOf $f.FullName
    $matches = @([regex]::Matches($text, '@RestControllerAdvice\b(?<args>\([^)]*\))?'))

    for ($i = 0; $i -lt $matches.Count; $i++) {
        $m = $matches[$i]

        # The region of source this advice owns: up to the next advice
        # declaration in the same file, or the end of the file. That is
        # what lets a file with several advices attribute the
        # @ExceptionHandler methods to the right one.
        $regionStart = $m.Index
        $regionEnd = if ($i + 1 -lt $matches.Count) { $matches[$i + 1].Index } else { $text.Length }
        $region = $text.Substring($regionStart, $regionEnd - $regionStart)

        $tail = $text.Substring($m.Index, [Math]::Min(1200, $text.Length - $m.Index))
        $classMatch = [regex]::Match($tail, '(?m)^\s*(?:public\s+|final\s+|abstract\s+|static\s+)*class\s+(?<n>[A-Za-z_][A-Za-z0-9_]*)')
        $className = if ($classMatch.Success) { $classMatch.Groups['n'].Value } else { '<unresolved>' }

        # @Order may be declared above the advice annotation.
        $headLength = [Math]::Min(600, $m.Index)
        $head = $text.Substring($m.Index - $headLength, $headLength)
        $orderMatch = [regex]::Match($region, '@Order\s*\((?<v>[^)]*)\)')
        if (-not $orderMatch.Success) { $orderMatch = [regex]::Match($head, '@Order\s*\((?<v>[^)]*)\)') }

        $orderExpr = if ($orderMatch.Success) { $orderMatch.Groups['v'].Value.Trim() } else { $null }
        $order = if ($orderExpr) { Resolve-OrderExpression -Expression $orderExpr } else { $null }

        $isFloor = $region -match '@ExceptionHandler\s*\(\s*\{?[^)]*\bException\.class'

        $advices.Add([ordered]@{
            class      = $className
            file       = $rel
            orderExpr  = $orderExpr
            order      = $order
            isFloor    = $isFloor
            selectors  = if ($m.Groups['args'].Success) { $m.Groups['args'].Value.Trim() } else { '' }
        })
    }
}

Write-QLog ("  advices found: {0}" -f $advices.Count) -Level INFO

# ---------------------------------------------------------------------
# FE-ADVICE-001 / 002 / 003 / 004 / 006
# ---------------------------------------------------------------------

$adv1 = New-Object System.Collections.Generic.List[object]
$adv2 = New-Object System.Collections.Generic.List[object]
$adv3 = New-Object System.Collections.Generic.List[object]
$adv4 = New-Object System.Collections.Generic.List[object]
$adv6 = New-Object System.Collections.Generic.List[object]

foreach ($a in $advices) {
    if (-not $a.orderExpr) {
        # The message is built into a variable before it goes into the hash literal: a
        # multi-line + concatenation as a hashtable VALUE is a parse error on Windows
        # PowerShell 5.1, and a gate that does not parse is not a gate.
        $m1 = "{0} declares no @Order. It will tie with every other unordered advice at " +
              'Ordered.LOWEST_PRECEDENCE and the winner is decided by component-scan ' +
              'registration order, which is the line order of a module list in a ' +
              'build.gradle.kts. Declare an explicit @Order.'
        $m6 = "{0} declares no order this script can resolve, so its position in the " +
              'advice ladder is unverified'
        $adv1.Add([ordered]@{
            rule = 'FE-ADVICE-001'; severity = 'FAIL'; file = $a.file
            detail = ($m1 -f $a.class)
        })
        $adv6.Add([ordered]@{
            rule = 'FE-ADVICE-006'; severity = 'FAIL'; file = $a.file
            detail = ($m6 -f $a.class)
        })
        continue
    }

    if ($null -eq $a.order) {
        $m = "{0} declares @Order({1}), which this script cannot resolve to an int. An " +
             'expression outside integer literals, the two Ordered constants, a ' +
             'static final int on another class, and + or - over those is unverifiable ' +
             'by a text scan, and an unverifiable order is not an order'
        $adv6.Add([ordered]@{
            rule = 'FE-ADVICE-006'; severity = 'FAIL'; file = $a.file
            detail = ($m -f $a.class, $a.orderExpr)
        })
        continue
    }
}

# Duplicates, over the advices whose order is known.
$byOrder = @{}
foreach ($a in $advices) {
    if ($null -eq $a.order -or -not $a.orderExpr) { continue }
    $key = [string] $a.order
    if (-not $byOrder.ContainsKey($key)) { $byOrder[$key] = New-Object System.Collections.Generic.List[string] }
    $byOrder[$key].Add($a.class)
}
foreach ($key in @($byOrder.Keys | Sort-Object)) {
    $names = @($byOrder[$key] | ForEach-Object { [string] $_ })
    if ($names.Count -lt 2) { continue }
    $m = "{0} all declare order {1}. They tie, and the winner between them is decided by " +
         'component-scan registration order. Give each a distinct @Order.'
    $owner = ($advices | Where-Object { $names -contains $_.class } | Select-Object -First 1)
    $adv2.Add([ordered]@{
        rule = 'FE-ADVICE-002'; severity = 'FAIL'
        file = if ($owner) { $owner.file } else { 'backend/**' }
        detail = ($m -f ($names -join ', '), $key)
    })
}

# Exactly one floor, and only the floor may sit at LOWEST_PRECEDENCE.
$floors = @($advices | Where-Object { $_.isFloor })
Write-QLog ("  catch-all floors: {0}" -f $floors.Count) -Level INFO
foreach ($a in $advices) {
    if ($null -eq $a.order -or -not $a.orderExpr) { continue }
    if (-not $a.isFloor -and $a.order -eq $ORDERED_LOWEST) {
        $m = "{0} declares Ordered.LOWEST_PRECEDENCE, which is the same order as the " +
             '@ExceptionHandler(Exception.class) catch-all. Spring stops at the first ' +
             'advice with a handler for the thrown type, so this advice and the floor ' +
             'race for every exception and the loser reads as fixed. Declare an order ' +
             'above LOWEST_PRECEDENCE.'
        $adv3.Add([ordered]@{
            rule = 'FE-ADVICE-003'; severity = 'FAIL'; file = $a.file
            detail = ($m -f $a.class)
        })
    }
}
if ($floors.Count -ne 1) {
    $detail = if ($floors.Count -eq 0) {
        ('no advice holds @ExceptionHandler(Exception.class). An exception that no advice claims ' +
         'leaves the dispatcher as whatever the container default is, which is a bare status with ' +
         'no FirstElement error body at all')
    }
    else {
        ('{0} advices hold @ExceptionHandler(Exception.class). Two catch-alls compete for every ' +
         'exception, and which one answers is decided by component-scan registration order') -f $floors.Count
    }
    $adv4.Add([ordered]@{
        rule = 'FE-ADVICE-004'; severity = 'FAIL'
        file = if ($floors.Count -gt 0) { $floors[0].file } else { 'backend/**' }
        detail = $detail
    })
}
elseif ($null -ne $floors[0].order -and $floors[0].order -ne $ORDERED_LOWEST) {
    $m = "{0} holds @ExceptionHandler(Exception.class) but declares order {1}. The " +
         'catch-all must be the last resort, or a specific refusal reaches it first and ' +
         'every 400/404/409 becomes a 500'
    $adv4.Add([ordered]@{
        rule = 'FE-ADVICE-004'; severity = 'FAIL'; file = $floors[0].file
        detail = ($m -f $floors[0].class, $floors[0].orderExpr)
    })
}

# ---------------------------------------------------------------------
# FE-ADVICE-005 (advisory)
# ---------------------------------------------------------------------

$adv5 = New-Object System.Collections.Generic.List[object]
foreach ($a in $advices) {
    if (-not $a.selectors) { continue }
    $m = "{0} is narrowed to `{1}`, so Spring consults it only for a controller that " +
         'matches. Any exception it would have handled, raised from a controller that ' +
         'does not match, falls to the catch-all. Legitimate for a module that owns one ' +
         'surface; for the security family it turns a cross-tenant denial into a 500 the ' +
         'day a controller lands outside the selector.'
    $adv5.Add([ordered]@{
        rule = 'FE-ADVICE-005'; severity = 'ADVISORY'; file = $a.file
        detail = ($m -f $a.class, $a.selectors)
    })
}

# ---------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------

$report = @(
    @{ key = 'FE-ADVICE-001'; title = 'every advice declares an explicit @Order'; list = $adv1 },
    @{ key = 'FE-ADVICE-002'; title = 'no two advices share an order'; list = $adv2 },
    @{ key = 'FE-ADVICE-003'; title = 'only the floor may sit at LOWEST_PRECEDENCE'; list = $adv3 },
    @{ key = 'FE-ADVICE-004'; title = 'exactly one catch-all floor, and it is last'; list = $adv4 },
    @{ key = 'FE-ADVICE-005'; title = 'narrowed advice scope (advisory)'; list = $adv5 },
    @{ key = 'FE-ADVICE-006'; title = 'every declared order is resolvable'; list = $adv6 }
)

$groups = New-Object System.Collections.Generic.List[object]
foreach ($g in $report) {
    $arr = ConvertTo-QArray -Value $g.list
    $outcome = if ($arr.Count -eq 0) { 'PASS' } else { if ($g.key -eq 'FE-ADVICE-005') { 'ADVISORY' } else { 'FAIL' } }
    $script:groups.Add([ordered]@{
        rule = $g.key; title = $g.title
        violationCount = $arr.Count; findings = $arr; outcome = $outcome
    })
    $mark = switch ($outcome) { 'PASS' { 'ok  ' } 'ADVISORY' { 'note' } default { 'FAIL' } }
    Write-QLog ("  {0} {1,-52} {2} finding(s)" -f $mark, $g.key, $arr.Count) `
        -Level $(if ($outcome -eq 'FAIL') { 'ERROR' } else { 'INFO' })
}

# Every rule list is folded into one flat finding list. The verdict is taken
# from THAT list and not from the per-rule outcomes, because a gate that
# reports FAIL in its own body and then exits 0 is the one failure mode this
# whole script exists to make impossible.
foreach ($g in $groups) {
    foreach ($f in @($g.findings)) { $script:findings.Add($f) }
}
$blocking = @($findings | Where-Object { $_.severity -eq 'FAIL' })
$verdict = if ($blocking.Count -eq 0) { 'PASS' } else { 'FAIL' }

$result = [ordered]@{
    check       = 'advice-order'
    verdict     = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    scannedSources = $totalSources
    adviceBearingSources = $scanned.Count
    includeTestSources = [bool] $IncludeTestSources
    advices     = ConvertTo-QArray -Value $advices
    rules       = ConvertTo-QArray -Value $groups
    findings    = ConvertTo-QArray -Value $findings
    scope       = 'backend/*/src/main/java (add -IncludeTestSources for test fixtures); advice order is only meaningful inside one assembled application'
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 10 | Out-Null }

Write-QLog ''
Write-QLog 'advice ladder:' -Level STEP
foreach ($a in ($advices | Sort-Object @{ Expression = { if ($null -eq $_.order) { [int]::MaxValue + 1 } else { $_.order } } })) {
    $shown = if ($null -eq $a.order) { 'UNORDERED' } else { [string] $a.order }
    $tag = if ($a.isFloor) { ' (floor)' } else { '' }
    Write-QLog ("  order {0,-12} {1}{2}   [{3}]" -f $shown, $a.class, $tag, $a.file) -Level INFO
}

Write-QLog ''
foreach ($f in $findings) {
    $level = if ($f.severity -eq 'FAIL') { 'ERROR' } else { 'WARN' }
    Write-QLog ("  {0} {1}: {2}" -f $f.rule, $f.file, $f.detail) -Level $level
}

Write-QLog ''
if ($verdict -eq 'FAIL') {
    Write-QLog 'advice-order VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'advice-order VERDICT: PASS -- the advice ladder is declared, unique, and the floor is last' -Level STEP
exit 0
