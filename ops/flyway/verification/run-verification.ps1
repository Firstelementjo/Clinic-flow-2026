#!/usr/bin/env pwsh
# =====================================================================
# run-verification.ps1
#
# Runs every Phase-2 verification query against a FirstElement PostgreSQL
# database and writes a timestamped evidence bundle. READ-ONLY: it never runs
# DDL or DML.
#
#   .\ops\flyway\verification\run-verification.ps1 `
#       -Container firstelement-postgres `
#       -Database  firstelement `
#       -OutputDir C:\...\evidence\2026-10-04-pre-baseline
#
# Add -ThrowOnViolation to turn the enum/fingerprint assertions into a
# non-zero exit code (used by CI and by the restore rehearsal).
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $Database  = 'firstelement',
    [string] $DbUser    = 'firstelement',
    [Parameter(Mandatory = $true)]
    [string] $OutputDir,
    # Expected number of enums in the public schema. The Phase-2 baseline is 23.
    # Phase 5a adds four (OutboxState, JobAttemptOutcome, DeadLetterStatus,
    # IdempotencyState) -> 27. Phase 6a adds seven (WorkflowVersionState,
    # WorkflowRunStatus, WorkflowActionOutcome, SlaCalendarState, SlaClockType,
    # CollisionScope, CollisionResolution) -> 34.
    #
    # Pass 0 to assert only that the two reconciled enums are intact and to
    # report the count without failing -- use that when the expected count is not
    # yet decided.
    #
    # These are ASSERTIONS, not switches to silence a failure. Never "fix" a
    # mismatch by editing this number: a mismatch means a migration changed an
    # enum value list. -ExpectedTableCount and -ExpectedCheckCount exist for the
    # same reason: a table or CHECK count that moves is a schema change that must
    # be recorded in docs/migration/flyway-runbook.md section 3, not a number
    # somebody updates in a script. Pass 0 for "do not assert".
    [int] $ExpectedEnumCount  = 23,
    [int] $ExpectedTableCount = 0,
    [int] $ExpectedCheckCount = 0,
    [switch] $ThrowOnViolation
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$queries   = Get-ChildItem -Path $scriptDir -Filter 'V*_*.sql' | Sort-Object Name

# ---------------------------------------------------------------------
# Pre-chain versus post-chain slices.
# ---------------------------------------------------------------------
# PHASE 41. This runner globs every V*_*.sql and runs it against whatever database it
# is pointed at, with no distinction between a slice that describes the BASELINE and
# one that describes the state AFTER the chain has been applied. That was harmless while
# every slice was baseline-only, and stopped being true the moment a later phase added a
# slice that asserts a post-chain object.
#
# V17_phase35_appointment_fk_slice.sql calls firstelement_next_customer_cx_id(), created
# by V20261007100000. Against `firstelement` -- the deliberately unstamped baseline -- that
# function does not exist, so V17 failed and took the backup/restore rehearsal with it. The
# failure was CORRECT and the wiring was wrong: the rehearsal takes a pre-restore reference
# from a pre-chain database, so it can only run pre-chain slices.
#
# A slice declares its own requirement with a `-- requires-chain: yes` marker in its
# header. When the target has no flyway_schema_history, such a slice is SKIPPED with the
# reason recorded in the summary -- named, not silent, because a silently skipped slice is
# indistinguishable from one that passed. `-- requires-chain: no` is the default and is
# the safe direction to be wrong in.
$hasChain = $false
try {
    $stampCheck = @"
SET search_path = '';
SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;
"@
    $previousEap2 = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $stampOut = ($stampCheck | docker exec -i $Container psql -U $DbUser -d $Database -t -A -f - 2>$null)
    $ErrorActionPreference = $previousEap2
    $hasChain = (($stampOut | ForEach-Object { $_.ToString().Trim() } | Select-Object -Last 1) -eq 't')
}
catch {
    Write-Host "could not determine whether $Database carries a Flyway stamp; treating it as PRE-CHAIN" -ForegroundColor Yellow
}
Write-Host ("target {0}: {1}" -f $Database, $(if ($hasChain) { 'CHAIN APPLIED' } else { 'PRE-CHAIN (no flyway_schema_history)' }))

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}

$summary = [ordered]@{
    startedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
    container     = $Container
    database      = $Database
    queries       = @()
}

foreach ($q in $queries) {
    $out = Join-Path $OutputDir ($q.BaseName + '.txt')
    Write-Host "==> $($q.Name)"
    $text = Get-Content -Raw -Path $q.FullName

    if ($text -match '(?m)^\s*--\s*requires-chain:\s*yes\s*$') {
        if (-not $hasChain) {
            $summary.queries += [ordered]@{
                name    = $q.Name
                file    = $null
                skipped = 'SKIPPED: declares -- requires-chain: yes, and the target carries no flyway_schema_history. This slice asserts post-chain state and cannot be evaluated on a baseline database.'
            }
            Write-Host "    SKIPPED (post-chain slice, target is pre-chain)" -ForegroundColor Yellow
            $skippedOut = Join-Path $OutputDir ($q.BaseName + '.txt')
            @(
                'SKIPPED: this slice declares -- requires-chain: yes.'
                "Target database '$Database' carries no flyway_schema_history, so the chain has not been"
                'applied and the objects this slice asserts do not exist yet.'
                'This is a wiring distinction, not a defect in the target: a backup rehearsal takes its'
                'pre-restore reference from a pre-chain database on purpose.'
            ) | Out-File -FilePath $skippedOut -Encoding utf8
            continue
        }
    }
    # Pipe the script into psql inside the container. No credentials are
    # printed; the container's local socket trust is used by `docker exec`.
    # psql writes NOTICE lines to stderr, so stderr is captured as text and
    # only $LASTEXITCODE decides success.
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $result = ($text | docker exec -i $Container psql -U $DbUser -d $Database `
                            -v ON_ERROR_STOP=1 -P pager=off -P 'fieldsep=|' -f - 2>&1) |
              ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $previousEap
    if ($LASTEXITCODE -ne 0) {
        $result | Out-File -FilePath $out -Encoding utf8
        throw "Verification query $($q.Name) failed; see $out"
    }
    $result | Out-File -FilePath $out -Encoding utf8
    $summary.queries += [ordered]@{ name = $q.Name; file = (Split-Path -Leaf $out) }
}

# --- assertions -----------------------------------------------------
# V1 emits one "<kind>|<object>|<detail>|<md5>" row per schema object.
# The fingerprint is the md5 of all of them: two databases with the same
# fingerprint are the same schema contract.
$fingerprintFile = Join-Path $OutputDir 'V1_schema_fingerprint.txt'
if (Test-Path $fingerprintFile) {
    $rows = Get-Content $fingerprintFile | Where-Object { $_ -match '\S' }
    $joined = ($rows -join "`n")
    $bytes  = [System.Text.Encoding]::UTF8.GetBytes($joined)
    $md5    = [System.Security.Cryptography.MD5]::Create().ComputeHash($bytes)
    $summary.schemaFingerprint = (($md5 | ForEach-Object { $_.ToString('x2') }) -join '')
    Write-Host "schema fingerprint: $($summary.schemaFingerprint)"
}

# V3's first result set must report the expected enum count and both
# reconciled enums OK.
$enumFile = Join-Path $OutputDir 'V3_enum_contract.txt'
if (Test-Path $enumFile) {
    $enumRow = (Get-Content $enumFile |
                Where-Object { $_ -match '^\s*\d+\s*\|' } |
                Select-Object -First 1)
    Write-Host "enum summary: $enumRow"
    $summary.expectedEnumCount = $ExpectedEnumCount

    $actual   = if ($enumRow -match '^\s*(\d+)\s*\|') { [int]$Matches[1] } else { -1 }
    $availOk    = if ($enumRow -match '\|\s*(\d+)\s*\|\s*(\d+)\s*$') { $Matches[1] } else { '0' }
    $decisionOk = if ($enumRow -match '\|\s*(\d+)\s*\|\s*(\d+)\s*$') { $Matches[2] } else { '0' }
    $summary.enumCount = $actual

    # The two reconciled enums must ALWAYS be exactly right, whatever the total.
    if ($availOk -eq '1' -and $decisionOk -eq '1') {
        $summary.enumContractOk = $true
    } else {
        $summary.enumContractOk = $false
        if ($ThrowOnViolation) {
            throw "Enum contract violated: EmployeeAvailabilityStatus / AIRunDecision are not intact (got '$enumRow')."
        }
    }

    if ($ExpectedEnumCount -eq 0) {
        $summary.enumCountAsserted = $false
        Write-Host "enum count: $actual (not asserted: -ExpectedEnumCount 0)"
    }
    elseif ($actual -eq $ExpectedEnumCount) {
        $summary.enumCountAsserted = $true
    }
    else {
        $summary.enumCountAsserted = $false
        Write-Host "enum count: $actual (expected $ExpectedEnumCount)"
        if ($ThrowOnViolation) {
            throw "Enum count violated. Expected '$ExpectedEnumCount' enums but got '$actual'. " +
                  "Either a migration added an enum (pass -ExpectedEnumCount <n>) or one changed a value list (stop)."
        }
    }
}

# --- table count ----------------------------------------------------
# V5's first result set row is the summary: extensions come first, so the row
# whose first field is a bare integer and whose column count is large is the
# tables/indexes summary. Rather than parse its shape, the count is taken from
# V11 section 4 -- which prints `tables` in a fixed, known position -- because
# that section has existed precisely to answer "how many tables does this
# database have".
if ($ExpectedTableCount -ne 0) {
    $tablesFile = Join-Path $OutputDir 'V11_workflow_sla_slice.txt'
    $actualTables = $null
    if (Test-Path $tablesFile) {
        # V11 section 4 prints " tables|<n>,| new_tables|<n>,| ..." across two
        # lines because of psql's field separator. Take the FIRST field of the
        # line that begins with " tables".
        # psql pads every column, so the header is " tables | new_tables | ..."
        # with a space BEFORE the separator as well. Both sides need \s*.
        # The DATA row is what carries the value, not the header: psql prints a header
        # line, a dashes line, then the row. Requiring exactly eight numeric
        # fields makes the match unambiguous against every other result set in
        # V11 (the single-value checks have one field, V4's summary has six).
        $row = (Get-Content $tablesFile |
                Where-Object { $_ -match '^\s*\d+(\s*\|\s*\d+){7}\s*$' } |
                Select-Object -First 1)
        if ($row) {
            $actualTables = [int]((($row -split '\|')[0]).Trim())
        }
    }
    if ($null -ne $actualTables) {
        $summary.expectedTableCount = $ExpectedTableCount
        $summary.tableCount        = $actualTables
        $summary.tableCountAsserted = ($actualTables -eq $ExpectedTableCount)
        Write-Host "table count: $actualTables (expected $ExpectedTableCount)"
        if ($actualTables -ne $ExpectedTableCount -and $ThrowOnViolation) {
            throw "Table count violated. Expected '$ExpectedTableCount' but got '$actualTables'. " +
                  "Record the change in docs/migration/flyway-runbook.md section 3 before adjusting this number."
        }
    }
    elseif ($ThrowOnViolation) {
        throw "-ExpectedTableCount $ExpectedTableCount was requested but V11 did not report a table count."
    }
}

# --- CHECK count ----------------------------------------------------
if ($ExpectedCheckCount -ne 0) {
    $checkFile = Join-Path $OutputDir 'V4_foreign_keys.txt'
    $actualChecks = $null
    if (Test-Path $checkFile) {
        # V4's summary row: foreign_keys|not_cascade_on_update|unsafe_on_delete|
        # primary_keys|unique_constraints|check_constraints
        # psql pads every column, so the separator is " | " and each field has
        # leading whitespace. \s* on both sides of every pipe is required; the
        # first version of this regex omitted it and silently matched nothing,
        # which the -ThrowOnViolation path then reported as "V4 did not report".
        $row = (Get-Content $checkFile |
                Where-Object { $_ -match '^\s*\d+(\s*\|\s*\d+){5}\s*$' } |
                Select-Object -First 1)
        if ($row) {
            $fields = ($row -split '\|') | ForEach-Object { $_.Trim() }
            if ($fields.Count -eq 6 -and $fields[5] -match '^\d+$') { $actualChecks = [int]$fields[5] }
        }
    }
    if ($null -ne $actualChecks) {
        $summary.expectedCheckCount  = $ExpectedCheckCount
        $summary.checkCount          = $actualChecks
        $summary.checkCountAsserted  = ($actualChecks -eq $ExpectedCheckCount)
        Write-Host "check count: $actualChecks (expected $ExpectedCheckCount)"
        if ($actualChecks -ne $ExpectedCheckCount -and $ThrowOnViolation) {
            throw "CHECK count violated. Expected '$ExpectedCheckCount' but got '$actualChecks'. " +
                  "A dropped CHECK constraint is a lost invariant; a new one must be recorded in the runbook."
        }
    }
    elseif ($ThrowOnViolation) {
        throw "-ExpectedCheckCount $ExpectedCheckCount was requested but V4 did not report a CHECK count."
    }
}

$summary.finishedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
$summary | ConvertTo-Json -Depth 5 |
    Out-File -FilePath (Join-Path $OutputDir 'summary.json') -Encoding utf8

Write-Host ""
Write-Host "Evidence bundle written to $OutputDir"
Get-Content (Join-Path $OutputDir 'summary.json')