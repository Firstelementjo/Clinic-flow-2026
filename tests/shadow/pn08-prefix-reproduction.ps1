#!/usr/bin/env pwsh
# =====================================================================
# pn08-prefix-reproduction.ps1
#
# THE PRE-FIX PN-08 EXPRESSION, VERBATIM AND STILL EXECUTABLE.
#
# Do not delete this. It exists so that the claim "PN-08's `generationOneFrozen`
# flag was a tautology" is not a story anyone has to take on trust: the exact
# code that was in check-shadow-parity.ps1 is kept here, unmodified apart from
# being handed its two files as parameters, and `pn08-proof.test.mjs` runs it
# and asserts the vacuous answer.
#
# THE DEFECT, IN ONE PARAGRAPH
# ---------------------------
# The first loop reads generation 1's `sourceEventCount` out of
# `$pn08Doc.olderGenerations` into `$baselineEvents`. The third loop then walks
# `$pn08Doc.olderGenerations` AGAIN and compares each generation-1 row's
# `sourceEventCount` against `$baselineEvents`. Same array, same in-memory
# document, same field. There is no input under which that comparison is false,
# so `$frozen` is `true` by construction. And when no generation-1 row is found
# at all, the guard `$baselineEvents -gt 0` skips the comparison entirely and
# `$frozen` still reports `true` -- a pass awarded for the ABSENCE of evidence.
#
# The second loop's "baseline" revenue is read from the POST-perturbation
# document rather than from the capture taken before the perturbation, so the
# reference moves with the thing under test: a recomputation that had rewritten
# generation 1 in place would have rewritten the baseline with it.
#
# WHAT IT SHOULD DO INSTEAD: `tests/shadow/pn08-proof.mjs`, which compares the
# PRE-perturbation capture against the POST-perturbation capture.
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Perturbed,   # the post-perturbation finance capture
    [Parameter(Mandatory = $true)][string] $Parity,      # the comparator's output over it
    [string] $Label = 'pn08'
)

$ErrorActionPreference = 'Stop'

$pn08Result = Get-Content -Raw -LiteralPath $Parity | ConvertFrom-Json
$derivation = $pn08Result.checks | Where-Object { $_.id -eq 'CHK-7-finance-derivation' }

# ---------------------------------------------------------------------
# Everything below is the pre-fix block, copied character for character
# from check-shadow-parity.ps1 (revision before the PN-08 fix).
# ---------------------------------------------------------------------
$pn08Doc = Get-Content -Raw -LiteralPath $Perturbed | ConvertFrom-Json
$moved = $false
$frozen = $true
$detail = @()
$baselineRevenue = 0.0
$baselineEvents = 0
foreach ($older in $pn08Doc.olderGenerations) {
    if ($older.dimension -eq 'MESSAGE' -and $older.generation -eq 1) {
        # Parenthesised on purpose. `[double]$older.revenue` binds the cast to
        # $older and then reads `.revenue` off a Double, which is null -- so the
        # baseline silently read 0 and the one comparison this perturbation exists
        # to make was always false.
        $baselineRevenue = [double]($older.revenue)
        $baselineEvents = $older.sourceEventCount
    }
}
foreach ($bucket in $derivation.evidence) {
    $detail += ("{0}: derived revenue {1} from {2} event(s)" -f $bucket.dimension, $bucket.stored.revenue, $bucket.stored.sourceEventCount)
    # The cast goes into its own statement on purpose. `[double]$bucket.stored.revenue`
    # inside a condition binds the cast to $bucket and then reads `.stored` off a
    # Double, which silently evaluates to nothing -- so the one comparison this
    # perturbation exists to make was quietly always false.
    $storedRevenue = [double]$bucket.stored.revenue
    if ($bucket.dimension -eq 'MESSAGE' -and ($storedRevenue - ($baselineRevenue + 3.0)) -eq 0.0) { $moved = $true }
}
foreach ($older in $pn08Doc.olderGenerations) {
    if ($older.generation -eq 1 -and $null -ne $older.revenue -and $baselineEvents -gt 0) {
        if ($older.sourceEventCount -ne $baselineEvents) { $frozen = $false }
    }
}
$pn08Ok = ($derivation.verdict -eq 'PASS' -and $moved -and $frozen)
# ---------------------------------------------------------------------

Write-Output ("{0}: pre-fix PN-08 -> proved={1} moved={2} frozen={3} baselineRevenue={4} baselineEvents={5} derivation={6}" -f `
    $Label, $pn08Ok, $moved, $frozen, $baselineRevenue, $baselineEvents, $derivation.verdict)
foreach ($line in $detail) { Write-Output ("    " + $line) }

# The script's own contract: exit 0 when the expression says proved.
exit $(if ($pn08Ok) { 0 } else { 1 })
