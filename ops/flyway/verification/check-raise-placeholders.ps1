#!/usr/bin/env pwsh
# =====================================================================
# check-raise-placeholders.ps1  (development aid, not a gate)
#
# Static parity check between RAISE placeholders and RAISE arguments in a
# plpgsql block. A mismatch makes the block fail to COMPILE -- "too few
# parameters specified for RAISE" or "too many parameters specified for RAISE"
# -- before any of its logic runs, so the whole migration aborts.
#
# WHAT COUNTS AS A PLACEHOLDER, MEASURED not assumed.
#   fixtures/raise-parser-probe.sql applies each form separately to PostgreSQL
#   16.15 and prints what it rendered. The results, which corrected the
#   assumption this tool was first written under:
#
#     '%'          placeholder                      (A)
#     '%s'         placeholder, 's' is the output format (B)
#     '%%'         a LITERAL percent, consumes nothing (D)
#     '= %'        the '=' is literal; this '%' IS a placeholder, so
#                  'valid= % unique %' has TWO and needs two arguments (C)
#     '%('         the '(' is LITERAL and '%s' is still a placeholder, so
#                  'column (%s)' has ONE and needs one argument (B)
#
#   The rule is: "%" is literal only when doubled ("%%"); otherwise it starts a
#   placeholder whose type character is whatever non-space follows it, and the
#   type character is consumed with it. So the count is simply the number of
#   non-doubled "%" -- NOT "the number of % followed by a non-space character",
#   which over-counts and is what an earlier draft of this file did.
#
# This caught two messages in
# V20261009110000__appointments_tenant_composite_fkeys.sql that read
# perfectly well and aborted the chain at compile time.
# =====================================================================
[CmdletBinding()]
param([string] $Path)

$ErrorActionPreference = 'Stop'
$lines = Get-Content -LiteralPath $Path
$bad = 0

for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -notmatch 'RAISE\s+(EXCEPTION|NOTICE)') { continue }
    $buf = ''; $j = $i
    do { $buf += ' ' + $lines[$j].Trim(); $j++ }
    while ($buf -notmatch ';\s*$' -and $j -lt $lines.Count)
    $stmt = ($buf -replace '\s+', ' ').Trim()
    # Split at the FINAL ";" -- the statement terminator. A lazy ".*?;" would
    # stop at the first ";" anywhere, and a message containing a ";" (there is
    # one in the org_notnull guard) would then be mis-parsed.
    $semi = $stmt.LastIndexOf(';')
    if ($semi -lt 0) { continue }
    $body = $stmt.Substring(0, $semi)      # everything before the terminator
    $m = [regex]::Match($body, '^RAISE (EXCEPTION|NOTICE)\s+(.*)$')
    if (-not $m.Success) { continue }

    # Groups[1] is the keyword, Groups[2] is "message, arg, arg, ...".
    # The message is every leading single-quoted literal; the first COMMA
    # OUTSIDE a quoted literal starts the argument list. A greedy regex cannot
    # do this, because a comma inside a literal looks identical from outside.
    $rest = $m.Groups[2].Value
    $msg = ''; $inStr = $false
    $argStart = -1
    for ($k = 0; $k -lt $rest.Length; $k++) {
        $ch = $rest[$k]
        if ($ch -eq "'") {
            if ($inStr -and ($k + 1) -lt $rest.Length -and $rest[$k + 1] -eq "'") { $k++; continue }
            $inStr = -not $inStr; $msg += $ch; continue
        }
        if (-not $inStr -and $ch -eq ',') { $argStart = $k; break }
        $msg += $ch
    }
    $args = if ($argStart -ge 0) { $rest.Substring($argStart + 1).Trim() } else { '' }

    # Concatenated string literals only -- a % inside a doubled-quote escape
    # (like '%.%' written via format()) is not part of the message.
    $literals = [regex]::Matches($msg, "'((?:[^']|'')*)'") | ForEach-Object { $_.Groups[1].Value }
    $text = ($literals -join '') -replace "''", "'"

    # A non-doubled "%" is a placeholder and consumes exactly one argument;
    # "%%" is a literal percent and consumes none. See the header for the
    # measurement that establishes this.
    $count = 0
    for ($k = 0; $k -lt $text.Length; $k++) {
        if ($text[$k] -ne '%') { continue }
        if ($k + 1 -lt $text.Length -and $text[$k + 1] -eq '%') { $k++; continue }
        $count++
    }

    $nargs = 0
    if ($args -ne '') {
        # Split on top-level commas only: parenthesised calls and quoted
        # strings both contain commas that are not argument separators.
        $depth = 0; $inStr = $false; $cur = ''
        foreach ($ch in $args.ToCharArray()) {
            if ($ch -eq "'") { $inStr = -not $inStr; $cur += $ch; continue }
            if (-not $inStr) {
                if ($ch -eq '(') { $depth++ }
                elseif ($ch -eq ')') { $depth-- }
                elseif ($ch -eq ',' -and $depth -eq 0) {
                    if ($cur.Trim()) { $nargs++ }; $cur = ''; continue
                }
            }
            $cur += $ch
        }
        if ($cur.Trim()) { $nargs++ }
    }

    $ok = ($count -eq $nargs)
    if (-not $ok) { $bad++ }
    Write-Host ("{0,5}  placeholders={1,-3} args={2,-3} {3}  {4}" -f `
        ($i + 1), $count, $nargs, $(if ($ok) { 'OK  ' } else { 'BAD ' }),
        $msg.Substring(0, [Math]::Min(70, $msg.Length)))
}

Write-Host ''
Write-Host ("{0} RAISE statement(s) checked, {1} with a placeholder/argument mismatch." -f `
    @($lines | Where-Object { $_ -match 'RAISE\s+(EXCEPTION|NOTICE)' }).Count, $bad)
exit $(if ($bad -gt 0) { 1 } else { 0 })