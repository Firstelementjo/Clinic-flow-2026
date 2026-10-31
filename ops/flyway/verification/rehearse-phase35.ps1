#!/usr/bin/env pwsh
# =====================================================================
# rehearse-phase35.ps1
#
# Positive and negative controls for
# V20261009110000__appointments_tenant_composite_fkeys.sql
#
# WHY THIS FILE EXISTS AT ALL
#   "a migration whose guard has never fired is a guard that does not work".
#   Every control below breaks EXACTLY ONE thing on a real, fully migrated
#   database and asserts that the migration goes RED -- and that the error is
#   the INTENDED one, not merely that something failed. Per ADR-0009 Ã‚Â§5 the
#   assertion is on STATE: the exit code AND the text of the refusal.
#
#   A control that only proved "some error occurred" would pass for the wrong
#   reason, which is the failure mode this project has hit repeatedly.
#
# EVERY THROWAWAY IS BUILT THE SAME WAY
#   Created from template0, migrated with the WHOLE chain INCLUDING
#   V20261009110000, then -- where the control needs the file genuinely
#   unapplied so it can hit its own guard -- has the four composite FKs and the
#   two target indexes dropped, and the file re-run. Rows are produced by
#   V17_phase35_appointment_fk_slice.sql, which asserts its own preconditions.
#
#   The negative controls that target the POST-CONDITION (NEG-6 .. NEG-10) are
#   built the other way: the chain applies normally, the object is broken, and
#   only the post-condition block is re-run -- because the migration body cannot
#   re-add objects that already exist.
#
# EVERY SQL SNIPPET LIVES IN ITS OWN FILE UNDER fixtures/
#   An earlier draft inlined them as here-strings, and PowerShell's escaping of
#   $ inside them silently corrupted a DO block ("error: invalid command \").
#   Every camelCase column is quoted for the same class of reason: an unquoted
#   "organizationId" folds to lowercase and fails with
#   'column "organizationid" of relation "appointments" does not exist'.
#
# -KeepControlDatabases leaves every throwaway in place for inspection.
#
# Exit codes: 0 every control behaved as specified, 1 one did not.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser    = 'firstelement',
    [string] $FlywayDir = 'ops/flyway/sql',
    [string] $Slice     = 'ops/flyway/verification/V17_phase35_appointment_fk_slice.sql',
    [switch] $KeepControlDatabases
)

$ErrorActionPreference = 'Continue'
$repoRoot  = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)))
$fixtures  = Join-Path $repoRoot 'ops/flyway/verification/fixtures'
$migration = 'V20261009110000__appointments_tenant_composite_fkeys.sql'

# The four composite FKs and the two indexes this file ADDS. Dropping exactly
# these makes the file genuinely unapplied without touching anything a later
# migration might depend on.
$undoObjects = @"
ALTER TABLE public.appointments
  DROP CONSTRAINT IF EXISTS appointments_created_by_tenant_fkey,
  DROP CONSTRAINT IF EXISTS appointments_customer_tenant_fkey,
  DROP CONSTRAINT IF EXISTS appointments_doctor_tenant_fkey,
  DROP CONSTRAINT IF EXISTS appointments_procedure_tenant_fkey;
DROP INDEX IF EXISTS public.doctors_id_organizationId_key;
DROP INDEX IF EXISTS public.procedures_id_organizationId_key;
"@

function Invoke-Psql {
    param([string] $Sql, [string] $Db)
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db `
                      -v ON_ERROR_STOP=1 -t -A -P pager=off -f - 2>&1) |
           ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $prev
    # psql WRAPS a long ERROR message onto several physical lines, so a phrase
    # like "its shape is not" can straddle a break and a naive -like on one line
    # reports a guard that actually fired as silent. Joining every ERROR/CONTEXT
    # line into one string makes the match independent of the terminal width.
    # Measured on PostgreSQL 16.15 with a 100-column message.
    $joined = (($out | Where-Object { $_ -match 'ERROR|CONTEXT' }) -join ' ') -replace '\s+', ' '
    [pscustomobject]@{ Code = $LASTEXITCODE; Out = $out; Joined = $joined }
}

function Get-Fixture {
    param([string] $Name)
    Get-Content -Raw (Join-Path $fixtures $Name)
}

# The post-condition block, extracted so a control can run JUST that half.
function Get-PostCondition {
    $full = Get-Content -Raw (Join-Path -Path (Join-Path -Path $repoRoot -ChildPath $FlywayDir) -ChildPath $migration)
    [regex]::Match($full, '(?s)DO \$postcondition\$.*\$postcondition\$;').Value
}

function New-Migrated {
    param([string] $Name)
    Invoke-Psql -Db $DbUser -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Name' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$Name";
CREATE DATABASE "$Name" TEMPLATE template0;
"@ | Out-Null
    $files = @(Get-ChildItem -LiteralPath (Join-Path -Path $repoRoot -ChildPath $FlywayDir) -Filter 'V*.sql' -File | Sort-Object Name)
    $sb = New-Object System.Text.StringBuilder
    foreach ($f in $files) { [void]$sb.AppendLine((Get-Content -Raw $f.FullName)) }
    $r = Invoke-Psql -Sql $sb.ToString() -Db $Name
    if ($r.Code -ne 0) {
        Write-Host ("  SETUP FAILED migrating {0}: {1}" -f $Name,
            (($r.Out | Where-Object { $_ -match 'ERROR' }) -join ' '))
        return $false
    }
    return $true
}

function Unapply {
    param([string] $Db)
    Invoke-Psql -Sql $undoObjects -Db $Db | Out-Null
}

function Invoke-Migration {
    param([string] $Db)
    $text = "SET search_path = '';`n" + (Get-Content -Raw (Join-Path -Path (Join-Path -Path $repoRoot -ChildPath $FlywayDir) -ChildPath $migration))
    $r = Invoke-Psql -Sql $text -Db $Db
    $err = ($r.Out | Where-Object { $_ -match 'ERROR' } | Select-Object -First 1)
    $err = if ($err) { [string]($err -replace '\s+', ' ') } else { '' }
    if ($err.Length -gt 140) { $err = $err.Substring(0, 140) }
    [pscustomobject]@{ Code = $r.Code; Err = $err; Full = $r.Joined }
}

$script:fail = 0
function Report {
    param([string] $Label, [bool] $Ok, [string] $Detail)
    if (-not $Ok) { $script:fail++ }
    Write-Host ("  {0}  {1}" -f $(if ($Ok) { 'PASS' } else { '*** FAIL ***' }), $Label)
    if ($Detail) { Write-Host ("        {0}" -f $Detail) }
}

# =====================================================================
# POSITIVE CONTROLS
# =====================================================================
Write-Host '=== POS-1  migration applies cleanly against legitimate data ==='
if (New-Migrated 'p35_pos1_check') {
    Invoke-Psql -Sql (Get-Content -Raw $Slice) -Db 'p35_pos1_check' | Out-Null
    Unapply 'p35_pos1_check'
    $r = Invoke-Migration -Db 'p35_pos1_check'
    Report -Label 'migration applies, exit 0' -Ok ($r.Code -eq 0) -Detail $r.Err
    $chk = Invoke-Psql -Db 'p35_pos1_check' -Sql @"
SELECT 'composites=' || count(*) FROM pg_constraint
 WHERE conrelid='public.appointments'::regclass AND contype='f' AND conname LIKE '%_tenant_fkey';
SELECT 'appointments=' || count(*) FROM public.appointments;
SELECT 'singles=' || count(*) FROM pg_constraint
 WHERE conrelid='public.appointments'::regclass AND contype='f'
   AND conname IN ('appointments_createdById_fkey','appointments_customerId_fkey',
                   'appointments_doctorId_fkey','appointments_procedureId_fkey');
"@
    $comp = ($chk.Out | Where-Object { $_ -match '^composites=' })
    $rows = ($chk.Out | Where-Object { $_ -match '^appointments=' })
    $sing = ($chk.Out | Where-Object { $_ -match '^singles=' })
    Report -Label '4 composite FKs installed' -Ok ($comp -eq 'composites=4') -Detail "$comp / $rows"
    Report -Label 'the 4 pre-existing single-column FKs are still present' -Ok ($sing -eq 'singles=4') -Detail $sing
}

Write-Host ''
Write-Host '=== POS-2  every reference NULL still applies (MATCH SIMPLE) ==='
if (New-Migrated 'p35_pos2_check') {
    Invoke-Psql -Db 'p35_pos2_check' -Sql @"
INSERT INTO public.organizations (id, name, slug, "createdAt", "updatedAt")
VALUES ('90000000-0000-0000-0000-0000000000f1','p35 null-ref org','p35-null-org', now(), now());
INSERT INTO public.projects (id, "organizationId", name, slug, "createdAt", "updatedAt")
VALUES ('90000000-0000-0000-0000-0000000000f2','90000000-0000-0000-0000-0000000000f1','p35 null-ref proj','p35-null-proj', now(), now());
INSERT INTO public.appointments (id, "organizationId", "projectId", title, "appointmentDate", status, "updatedAt")
VALUES ('90000000-0000-0000-0000-0000000000f3','90000000-0000-0000-0000-0000000000f1',
        '90000000-0000-0000-0000-0000000000f2','all four references null', now(), 'PENDING', now());
"@ | Out-Null
    Unapply 'p35_pos2_check'
    $r = Invoke-Migration -Db 'p35_pos2_check'
    Report -Label 'appointment with all four references NULL applies' -Ok ($r.Code -eq 0) -Detail $r.Err
}

Write-Host ''
Write-Host '=== POS-3  the constraints actually REFUSE a cross-tenant write ==='
if (New-Migrated 'p35_pos3_check') {
    Invoke-Psql -Sql (Get-Content -Raw $Slice) -Db 'p35_pos3_check' | Out-Null
    $probe = Invoke-Psql -Sql (Get-Fixture 'p35_pos3_cross_tenant_write.sql') -Db 'p35_pos3_check'
    Report -Label 'cross-tenant doctorId refused by the constraint' `
           -Ok ($probe.Code -ne 0 -and $probe.Joined -match 'tenant_fkey') `
           -Detail ($probe.Joined)
}

Write-Host ''
Write-Host '=== POS-4  deleting a doctor still works and NULLs ONLY doctorId ==='
if (New-Migrated 'p35_pos4_check') {
    Invoke-Psql -Sql (Get-Content -Raw $Slice) -Db 'p35_pos4_check' | Out-Null
    $r = Invoke-Psql -Sql (Get-Fixture 'p35_pos4_delete_propagation.sql') -Db 'p35_pos4_check'
    Report -Label 'DELETE of a doctor NULLs doctorId and preserves the appointment' `
           -Ok ($r.Code -eq 0 -and $r.Joined -notmatch 'ERROR') -Detail ($r.Joined)
    ($r.Out | Where-Object { $_ -match 'P35_DELETE_OK' }) | ForEach-Object { Write-Host "        $_" }
}

# =====================================================================
# NEGATIVE CONTROLS, PART 1: the guards that run BEFORE the constraint exists
# =====================================================================
Write-Host ''
Write-Host '=== NEG-1..NEG-5  refuse-rather-than-repair: each must refuse and NAME the ids ==='

$negatives = @(
    @{ id='p35_neg1'; file='p35_neg1_cross_tenant_doctor.sql'
       expect='disagrees with the organization owning the doctors row'
       label='seeded CROSS-TENANT doctorId' },
    @{ id='p35_neg2'; file='p35_neg2_cross_tenant_customer.sql'
       expect='disagrees with the organization owning the customers row'
       label='seeded CROSS-TENANT customerId' },
    @{ id='p35_neg3'; file='p35_neg3_cross_tenant_procedure.sql'
       expect='disagrees with the organization owning the procedures row'
       label='seeded CROSS-TENANT procedureId' },
    @{ id='p35_neg4'; file='p35_neg4_cross_tenant_creator.sql'
       expect='disagrees with the organization owning the users row'
       label='seeded CROSS-TENANT createdById' },
    @{ id='p35_neg5'; file='p35_neg5_orphan_doctor.sql'
       expect='whose id is absent from the target table'
       label='ORPHAN doctorId (no matching doctors row)' }
)

foreach ($c in $negatives) {
    Write-Host ("--- {0} : {1}" -f $c.id, $c.label)
    if (-not (New-Migrated $c.id)) { Report -Label 'control setup' -Ok $false; continue }
    $rs = Invoke-Psql -Sql (Get-Content -Raw $Slice) -Db $c.id
    if ($rs.Code -ne 0) {
        Report -Label 'slice seeded' -Ok $false -Detail (($rs.Out | Where-Object { $_ -match 'ERROR' }) -join ' '); continue
    }
    Unapply $c.id
    $rb = Invoke-Psql -Sql (Get-Fixture $c.file) -Db $c.id
    if ($rb.Code -ne 0) {
        Report -Label 'seeded the offending row' -Ok $false -Detail (($rb.Out | Where-Object { $_ -match 'ERROR' }) -join ' '); continue
    }
    Report -Label 'the offending row IS insertable without the composite FK' -Ok $true

    $r = Invoke-Migration -Db $c.id
    Report -Label ("migration REFUSED (exit {0}, expected non-zero)" -f $r.Code) -Ok ($r.Code -ne 0) -Detail $r.Err
    Report -Label ("the INTENDED assertion fired -- looked for: '{0}'" -f $c.expect) -Ok ($r.Full -like ('*' + $c.expect + '*'))

    # THE POINT OF THE WHOLE EXERCISE: the constraint is ABSENT after the
    # refusal, and the offending row is STILL THERE -- this migration repairs
    # nothing and deletes nothing.
    $after = Invoke-Psql -Db $c.id -Sql @"
SELECT 'composites=' || count(*) FROM pg_constraint
 WHERE conrelid='public.appointments'::regclass AND contype='f' AND conname LIKE '%_tenant_fkey';
"@
    $line = ($after.Out | Where-Object { $_ -match '^composites=' })
    Report -Label 'no composite FK left behind after the refusal' -Ok ($line -eq 'composites=0') -Detail $line

    $rows = Invoke-Psql -Db $c.id -Sql "SELECT 'appointments=' || count(*) FROM public.appointments;"
    $arow = ($rows.Out | Where-Object { $_ -match '^appointments=' })
    Report -Label 'the offending row was NOT deleted (refuse, do not repair)' -Ok ([int]($arow -replace 'appointments=', '') -ge 2) -Detail $arow
}

# =====================================================================
# NEGATIVE CONTROLS, PART 2: the post-condition assertions
# =====================================================================
Write-Host ''
Write-Host '=== NEG-6..NEG-10  the post-condition must catch a constraint that LOOKS right ==='

$postconds = @(
    @{ id='p35_neg6'; file='p35_neg6_reversed_pair.sql'
       expect='its shape is not'; label='composite constraint REVERSED to (organizationId, doctorId)' },
    @{ id='p35_neg7'; file='p35_neg7_partial_target_index.sql'
       expect='PARTIAL'; label='target index made PARTIAL' },
    @{ id='p35_neg8'; file='p35_neg8_organization_not_null.sql'
       expect='no longer NOT NULL'; label='appointments.organizationId NOT NULL dropped (MATCH SIMPLE tripwire)' },
    @{ id='p35_neg9'; file='p35_neg9_single_column_lookalike.sql'
       expect='its shape is not'; label='composite replaced by a SINGLE-column lookalike of the same name' },
    @{ id='p35_neg10'; file='p35_neg10_cascade_delete.sql'
       expect='does not have ON DELETE SET NULL'; label='composite kept but ON DELETE changed to CASCADE' }
)

foreach ($c in $postconds) {
    Write-Host ("--- {0} : {1}" -f $c.id, $c.label)
    if (-not (New-Migrated $c.id)) { Report -Label 'control setup' -Ok $false; continue }
    $ra = Invoke-Psql -Sql (Get-Fixture $c.file) -Db $c.id
    if ($ra.Code -ne 0) {
        Report -Label 'applied the break' -Ok $false -Detail (($ra.Out | Where-Object { $_ -match 'ERROR' }) -join ' '); continue
    }
    $pc = Get-PostCondition
    if (-not $pc) { Report -Label 'located the post-condition block' -Ok $false; continue }
    $r = Invoke-Psql -Sql ("SET search_path = '';`n" + $pc) -Db $c.id
    $err = $r.Joined
    if ($err.Length -gt 140) { $err = $err.Substring(0, 140) }
    Report -Label ("post-condition REFUSED (exit {0}, expected non-zero)" -f $r.Code) -Ok ($r.Code -ne 0) -Detail $err
    Report -Label ("the INTENDED assertion fired -- looked for: '{0}'" -f $c.expect) -Ok ($r.Joined -like ('*' + $c.expect + '*'))
}

# =====================================================================
$allDbs = @('p35_pos1_check','p35_pos2_check','p35_pos3_check','p35_pos4_check') +
          ($negatives.id) + ($postconds.id)

Write-Host ''
if (-not $KeepControlDatabases) {
    Write-Host 'disposing control databases'
    foreach ($n in $allDbs) {
        Invoke-Psql -Db $DbUser -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$n' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$n";
"@ | Out-Null
    }
    Write-Host 'all control databases dropped'
} else {
    Write-Host ('-KeepControlDatabases: left in place -- ' + ($allDbs -join ', '))
}

Write-Host ''
if ($fail -gt 0) {
    Write-Host "RESULT: $fail control(s) did not behave as specified." -ForegroundColor Red
    exit 1
}
Write-Host 'RESULT: every control behaved as specified.' -ForegroundColor Green
exit 0