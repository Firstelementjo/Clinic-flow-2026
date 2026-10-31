#!/usr/bin/env pwsh
# =====================================================================
# Invoke-ForensicScan.ps1
#
# Runs the cross-tenant forensic query against a database and reports
# what it found AND what it could not see. The second half is the point:
# a query that returns zero on a database with no audit coverage is not
# a clean result, it is an absent one, and this script refuses to print
# the word "clean" in that case.
#
# Guard shape, deliberately identical to ops/quality/checks/
# prepare-throwaway-db.ps1 rather than a second convention:
#
#   1. the target must differ from -ProtectedDatabase (`firstelement`)
#   2. the target must LOOK like a forensics target: it has to contain
#      `forensic`. A forensics database must never be one typo away from
#      development or production
#   3. the name must be a plain identifier
#   4. the session is opened READ ONLY via PGOPTIONS
#      default_transaction_read_only=on, so the SQL cannot write even if
#      it is edited to try
#
# It never reads .env, never takes a password, and reaches the database
# only through the container's local socket via `docker exec`, exactly
# like prepare-throwaway-db.ps1 and run-verification.ps1.
#
# It never selects patient data. Rule FE-FOR-REDACT-001 greps the SQL
# for the clinical columns the forensic report has no business
# containing and refuses to execute if any is present. That includes
# audit_logs.metadata, which the clinic service populates with
# patientPhone and doctorName.
#
# Exit codes:
#   0  scan ran; no findings, AND coverage was sufficient to say so
#   1  findings (a positive result -- this is the interesting one)
#   2  bad invocation / refused target
#   3  the scan ran but coverage was insufficient to conclude anything
#      (INCONCLUSIVE. Deliberately NOT 0: nobody should wire this into a
#      pipeline where "could not tell" and "nothing found" are the same)
# =====================================================================
[CmdletBinding()]
param(
    # The database to scan. Must contain `forensic`.
    [Parameter(Mandatory = $true)]
    [string] $Database,

    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $ProtectedDatabase = 'firstelement',

    # Cap on how many resource ids a single finding lists.
    [int] $MaxIds = 50,

    [string] $SqlPath,
    [string] $JsonOut
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\quality\lib\quality-common.ps1')

if (-not $SqlPath) { $SqlPath = Join-Path $PSScriptRoot 'sql\F-cross-tenant-access.sql' }

# --- refusals --------------------------------------------------------
if ($Database -eq $ProtectedDatabase) {
    Write-QLog "REFUSING: -Database '$Database' is the protected database. A forensic scan is read-only, but it is still a read of the one database this repository must never touch by accident." -Level ERROR
    Write-QLog '          Take a copy (pg_dump --schema-only plus a data dump, or a restored backup) and scan the copy.' -Level ERROR
    exit 2
}
if ($Database -notmatch '(?i)forensic') {
    Write-QLog ("REFUSING: '{0}' does not look like a forensics target. A forensic database name must contain the substring 'forensic', so that a scan target is identifiable in pg_stat_activity, in a process list and in a backup directory listing." -f $Database) -Level ERROR
    exit 2
}
if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    Write-QLog "REFUSING: '$Database' is not a plain identifier." -Level ERROR
    exit 2
}
if (-not (Test-Path -LiteralPath $SqlPath)) {
    Write-QLog "REFUSING: forensic SQL not found at '$SqlPath'." -Level ERROR
    exit 2
}

# --- FE-FOR-REDACT-001: no patient data in the query ------------------
# A comment promising not to select patient data is not a control. This
# is.
#
# The check runs over the SQL with `--` comments stripped, because the
# forensic query is *required* to document why it refuses to select
# audit_logs.metadata (the clinic service writes patientPhone and
# doctorName into it). Stripping comments rather than relaxing the token
# list keeps the rule about what is SELECTed, which is where the risk
# actually is, and keeps the file able to explain itself.
$forbidden = @(
    'patientName', 'patientPhone', 'patientEmail', 'doctorName',
    'displayName', 'primaryPhone', 'primaryEmail', 'fullName',
    'procedureName', 'aiProposalReason', 'ticketNumber', 'passwordHash',
    '\bmetadata\b', '\bnotes\b', '\btitle\b', '\bservice\b', '\baddress\b'
)
$sqlText = Get-Content -Raw -LiteralPath $SqlPath
$executableSql = (($sqlText -split "`r?`n") |
    Where-Object { $_ -notmatch '^\s*--' }) -join "`n"
$leaks = New-Object System.Collections.Generic.List[string]
foreach ($token in $forbidden) {
    if ($executableSql -match "(?i)$token") { $leaks.Add($token) }
}
if ($leaks.Count -gt 0) {
    Write-QLog ("REFUSING: the forensic SQL selects {0}. A forensics report is read by people who do not need to see patients in order to act, and must carry ids and counts only." -f (($leaks | Sort-Object -Unique) -join ', ')) -Level ERROR
    exit 2
}

function Invoke-ReadOnlyPsql {
    param([string] $Sql, [string] $Db, [string[]] $ExtraArgs = @())
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # NOT $args: that is an automatic variable in every PowerShell
    # function, and assigning to it is how a script ends up invoking the
    # wrong binary without an error.
    $dockerArgs = @('exec', '-i', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
                    $Container, 'psql',
                    '-U', $DbUser, '-d', $Db,
                    '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-F', '|', '-P', 'pager=off')
    foreach ($a in $ExtraArgs) { $dockerArgs += $a }
    $dockerArgs += @('-f', '-')
    $out = ($Sql | & docker @dockerArgs 2>&1) | ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $previous
    return [ordered]@{ output = @($out); exitCode = $LASTEXITCODE }
}

# --- execute ---------------------------------------------------------
Write-QLog ("==> forensic scan of {0} (read-only, max {1} ids per finding)" -f $Database, $MaxIds) -Level STEP

$psqlArgs = @('-v', ('maxids=' + $MaxIds))
$run = Invoke-ReadOnlyPsql -Sql $sqlText -Db $Database -ExtraArgs $psqlArgs

if ($run.exitCode -ne 0) {
    Write-QLog ("scan failed (psql exit {0}):" -f $run.exitCode) -Level ERROR
    foreach ($o in $run.output) { if ($o) { Write-QLog ('    ' + $o) -Level ERROR } }
    exit 2
}

# --- parse -----------------------------------------------------------
# Every emitted row has the same eight columns. A row that does not is
# treated as an error rather than skipped: silently dropping rows from a
# forensic report is how a finding goes missing without anyone noticing.
$findings   = New-Object System.Collections.Generic.List[object]
$coverage   = New-Object System.Collections.Generic.List[object]
$bySeverity = @{ CRITICAL = 0; HIGH = 0; MEDIUM = 0; INFO = 0 }
$perCheck   = New-Object System.Collections.Generic.List[object]
$malformed  = 0

foreach ($line in $run.output) {
    if (-not $line) { continue }
    if ($line -eq 'BEGIN' -or $line -eq 'COMMIT') { continue }
    $cols = $line -split '\|'
    if ($cols.Count -lt 8) { $malformed++; continue }
    # PSCustomObject, not an ordered hashtable: Group-Object resolves
    # PSObject properties, not dictionary keys, so an ordered hashtable
    # silently groups every finding under one unnamed group.
    $rec = [PSCustomObject][ordered]@{
        section        = $cols[0].Trim()
        checkId        = $cols[1].Trim()
        severity       = $cols[2].Trim()
        organizationA  = $cols[3].Trim()
        organizationB  = $cols[4].Trim()
        resourceId     = $cols[5].Trim()
        occurredAt     = $cols[6].Trim()
        note           = $cols[7].Trim()
    }
    if ($rec.section -eq 'FINDING') {
        $findings.Add($rec)
        if ($bySeverity.ContainsKey($rec.severity)) { $bySeverity[$rec.severity] = $bySeverity[$rec.severity] + 1 }
    }
    elseif ($rec.section -eq 'COVERAGE') {
        $coverage.Add($rec)
    }
    else { $malformed++ }
}

if ($malformed -gt 0) {
    Write-QLog ("REFUSING: {0} output row(s) did not match the eight-column forensic contract. A forensic report that silently drops rows is worse than no report." -f $malformed) -Level ERROR
    exit 2
}

# --- the scanner verifies its own read-only guard -------------------
# This is the check that makes the rehearsal's R3 meaningful. R3 proves
# PostgreSQL honours default_transaction_read_only; this proves THIS
# scanner is running under it. Without this assertion, a scanner that
# lost its guard would still produce a perfectly plausible report, and
# the rehearsal would stay green.
$sessionReadOnly = 'unknown'
foreach ($c in $coverage) {
    if ($c.checkId -eq 'FE-FOR-C07' -and $c.note -match '^SESSION_DEFAULT_READ_ONLY=(.+)$') {
        $sessionReadOnly = $Matches[1].Trim()
    }
}
if ($sessionReadOnly -ne 'on') {
    Write-QLog ("REFUSING: the session reports default_transaction_read_only='{0}', not 'on'. A forensic scan must be unable to write even if its SQL is edited to try, so a scan running without the read-only guard produces no report at all rather than an unverified one." -f $sessionReadOnly) -Level ERROR
    exit 2
}

# --- coverage interpretation ------------------------------------------
# The distinction the whole tool exists to make.
$rowCounts    = @{}
$auditTotal   = 0
$apptTotal    = 0
$apptNoAudit  = 0
$rosterAudit  = 0
$auditWindow  = 'NONE..NONE'
$clinicWindow = 'NONE'
foreach ($c in $coverage) {
    if ($c.checkId -eq 'FE-FOR-C02' -and $c.note -match '^TABLE_ROW_COUNT:([A-Z_]+)=(\d+)$') {
        $rowCounts[$Matches[1]] = [int]$Matches[2]
        if ($Matches[1] -eq 'AUDIT_LOGS')   { $auditTotal = [int]$Matches[2] }
        if ($Matches[1] -eq 'APPOINTMENTS') { $apptTotal  = [int]$Matches[2] }
    }
    if ($c.checkId -eq 'FE-FOR-C03' -and $c.note -match 'APPOINTMENTS_WITH_NO_AUDIT_ROW_AT_ALL=(\d+)') { $apptNoAudit = [int]$Matches[1] }
    if ($c.checkId -eq 'FE-FOR-C04' -and $c.note -match 'ROSTER_AUDIT_ACTION_ROWS=(\d+)') { $rosterAudit = [int]$Matches[1] }
    if ($c.checkId -eq 'FE-FOR-C05') { $auditWindow  = $c.note -replace '^AUDIT_WINDOW=', '' }
    if ($c.checkId -eq 'FE-FOR-C06') { $clinicWindow = $c.note -replace '^CLINICAL_ROW_WINDOW=', '' }
}
function Get-RowCount {
    param([string] $Kind)
    if ($rowCounts.ContainsKey($Kind)) { return $rowCounts[$Kind] }
    return 0
}

$auditedAppointmentActions = @($coverage | Where-Object {
    $_.checkId -eq 'FE-FOR-C01' -and $_.note -match '^AUDITED_ACTION_COUNT:APPOINTMENT_'
}).Count

# Coverage is sufficient to make a claim only if there is at least one
# appointment lifecycle audit row to have mismatched, AND clinical rows
# actually exist that the gap would apply to.
$hasAuditCoverage = ($auditedAppointmentActions -gt 0)
$hasClinicalRows  = ($apptTotal -gt 0)
$canConclude      = ($hasAuditCoverage -and $hasClinicalRows)

# --- verdict ---------------------------------------------------------
if ($findings.Count -gt 0)      { $verdict = 'FINDINGS';           $exit = 1 }
elseif ($canConclude)            { $verdict = 'CLEAN';              $exit = 0 }
else                            { $verdict = 'INCONCLUSIVE';       $exit = 3 }

Write-QLog ''
Write-QLog '== WHAT THE DATABASE LOOKED LIKE ==' -Level STEP
Write-QLog ("  organizations {0}   projects {1}   users {2}" -f `
    (Get-RowCount 'ORGANIZATIONS'), (Get-RowCount 'PROJECTS'), (Get-RowCount 'USERS')) -Level INFO
Write-QLog ("  appointments {0}   doctors {1}   procedures {2}   tasks {3}   customers {4}" -f `
    $apptTotal, (Get-RowCount 'DOCTORS'), (Get-RowCount 'PROCEDURES'), `
    (Get-RowCount 'TASKS'), (Get-RowCount 'CUSTOMERS')) -Level INFO
Write-QLog ("  audit rows total {0}   appointment lifecycle actions present {1}" -f $auditTotal, $auditedAppointmentActions) -Level INFO
Write-QLog ("  appointments carrying no audit row at all : {0} of {1}" -f $apptNoAudit, $apptTotal) -Level INFO
Write-QLog ("  doctor/procedure lifecycle audit rows : {0}" -f $rosterAudit) -Level INFO
Write-QLog ("  audit window : {0}" -f $auditWindow) -Level INFO
Write-QLog ("  clinical row window : {0}" -f $clinicWindow) -Level INFO

Write-QLog ''
Write-QLog '== FINDINGS ==' -Level STEP
if ($findings.Count -eq 0) {
    Write-QLog '  none' -Level INFO
}
else {
    $grouped = $findings | Group-Object checkId | Sort-Object Name
    foreach ($g in $grouped) {
        Write-QLog ("  {0}  {1} finding(s)  severity {2}" -f $g.Name, $g.Count, (@($g.Group)[0].severity)) -Level WARN
        foreach ($f in @($g.Group)) {
            $pair = if ($f.organizationB) { "$($f.organizationA) -> $($f.organizationB)" } else { $f.organizationA }
            Write-QLog ("        {0}  actor-org {1}  row {2}  at {3}  {4}" -f $f.resourceId, $pair, $f.resourceId, $f.occurredAt, $f.note) -Level INFO
        }
    }
}

# --- the limits, printed as loudly as the findings -------------------
Write-QLog ''
Write-QLog '== WHAT THIS RESULT DOES NOT RULE OUT ==' -Level STEP
$limits = New-Object System.Collections.Generic.List[string]

if (-not $hasAuditCoverage) {
    $limits.Add(('NO AUDIT COVERAGE. This database holds {0} audit row(s) and {1} appointment-lifecycle audit row(s). The cross-tenant lead compares an audit row against its target; with no such rows there is nothing to compare. A zero here means I COULD NOT LOOK, not that nothing happened.' -f $auditTotal, $auditedAppointmentActions))
}
if ($rosterAudit -eq 0) {
    $limits.Add('ROSTER WRITES ARE INVISIBLE. This database contains zero doctor or procedure audit rows. Create, update and delete on the clinical roster leave no trace whatsoever, so no query over this schema can answer whether the roster was altered. Only persistent residue (a roster row sitting in the wrong tenant, FE-FOR-006) is visible, and only if it was never reverted.')
}
if (-not $hasClinicalRows) {
    $limits.Add(('NO CLINICAL ROWS. {0} appointments exist. There is no state for a breach to have left behind, so the residue checks are vacuous.' -f $apptTotal))
}
$limits.Add('READS ARE INVISIBLE. No read writes an audit row anywhere in this system. Every path in the incident description that only READ -- rosters, appointments, another tenant''s patient contact details -- is undetectable by any query over this schema, regardless of outcome.')
$limits.Add('UNAUTHENTICATED ACCESS IS INVISIBLE. The @Public() reminder routes that returned a patient name, phone and doctor from a bare appointment UUID wrote no audit row. No database query can distinguish "never called" from "called ten thousand times".')
$limits.Add('REVERTED WRITES ARE INVISIBLE. FE-FOR-005 and FE-FOR-006 look for residue that persists. A cross-tenant write that was later corrected, or a deletion, leaves this query nothing to find.')
$limits.Add('BLIND WINDOW. Anything before the first audit row timestamp ({0}) is outside what the audit table can describe, whatever the outcome.' -f ($auditWindow -split '\.\.')[0])
$limits.Add('ADJUDICATION REQUIRED ON FE-FOR-004 AND FE-FOR-008. A user moved legitimately between tenants, or granted membership after the fact, produces the same shape as an attacker. These are leads to investigate, not conclusions.')
foreach ($l in $limits) { Write-QLog ('  - ' + $l) -Level WARN }

Write-QLog ''
if ($verdict -eq 'FINDINGS') {
    Write-QLog ('VERDICT: FINDINGS -- {0} critical, {1} high, {2} medium. TREAT AS AN ACTIVE INCIDENT: see ops/forensics/RESPONSE.md before doing anything else.' -f $bySeverity.CRITICAL, $bySeverity.HIGH, $bySeverity.MEDIUM) -Level ERROR
}
elseif ($verdict -eq 'CLEAN') {
    Write-QLog 'VERDICT: CLEAN WITHIN COVERAGE -- no cross-tenant mismatch found in the audited appointment actions, and coverage was sufficient to look. This is NOT an all-clear: read the limits above, which apply to this result exactly as they would to a positive one.' -Level STEP
}
else {
    Write-QLog 'VERDICT: INCONCLUSIVE -- the query ran but the database could not support an answer. This is explicitly NOT a clean result. Exit code 3, so "could not tell" cannot be mistaken for "nothing found" by a caller that only checks for zero.' -Level ERROR
}

$result = [ordered]@{
    check              = 'forensic-cross-tenant-access'
    verdict            = $verdict
    exitCode           = $exit
    database           = $Database
    protectedDatabase  = $ProtectedDatabase
    scannedAtUtc       = (Get-Date).ToUniversalTime().ToString('o')
    maxIdsPerFinding   = $MaxIds
    readOnly           = $true
    sessionReadOnly    = $sessionReadOnly
    patientDataEmitted = $false
    coverage           = [ordered]@{
        auditRowsTotal              = $auditTotal
        appointmentLifecycleActions = $auditedAppointmentActions
        appointments                = $apptTotal
        appointmentsWithNoAuditRow  = $apptNoAudit
        rosterLifecycleAuditRows    = $rosterAudit
        auditWindow                 = $auditWindow
        clinicalRowWindow           = $clinicWindow
        canConclude                 = $canConclude
    }
    severityCounts = [ordered]@{
        critical = $bySeverity.CRITICAL
        high     = $bySeverity.HIGH
        medium   = $bySeverity.MEDIUM
    }
    findings = ConvertTo-QArray -Value $findings
    limits   = ConvertTo-QArray -Value $limits
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 8 | Out-Null }

exit $exit