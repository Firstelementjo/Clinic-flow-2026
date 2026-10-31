#!/usr/bin/env pwsh
# =====================================================================
# rehearse-undo-phase35.ps1
#
# Rehearses ops/flyway/rollback/undo_phase35.sql on DISPOSABLE databases.
# Never on firstelement.
#
# THE THREE OUTCOMES THAT MATTER
#   A. unarmed               -> nothing touched, exit 0, PHASE35_UNDO_SKIPPED
#   B. armed, clean database -> PHASE35_REMOVED, and the OBJECT COUNTS and the
#                              fingerprint return to the post-Phase-14 values.
#                              This is the case the undo exists for.
#   C. armed, a cross-tenant appointment present -> REFUSED, and NOTHING is
#                              removed. This is the guard that keeps the undo from
#                              re-opening the disclosure Phase 35 closed.
#   D. partial batch          -> REFUSED. Running the undo out of order fails
#                              loudly instead of compounding a half state.
#
# A rehearsal that only proved "it ran" would prove nothing; each row below
# asserts the exit code AND the surviving state.
#
# Exit codes: 0 every outcome matched, 1 one did not.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser    = 'firstelement',
    [string] $Undo      = 'ops/flyway/rollback/undo_phase35.sql',
    [string] $FlywayDir = 'ops/flyway/sql',
    [switch] $KeepControlDatabases
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)))

function Invoke-Psql {
    param([string] $Sql, [string] $Db, [hashtable] $Vars = @{})
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    # "-v name=value" with NO space before the name. PowerShell splits an
    # argument on whitespace, so "-v $($_.Key)=..." arrives at psql as
    # -v, " firstelement_undo_armed=on" and psql reports
    # 'invalid variable name: " firstelement_undo_armed"'. The undo is then
    # never armed and every "armed" rehearsal silently tested the unarmed path.
    $flags = ($Vars.GetEnumerator() | ForEach-Object { "-v$($_.Key)=$($_.Value)" }) -join ' '
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db `
                      -v ON_ERROR_STOP=1 -t -A -P pager=off $flags -f - 2>&1) |
           ForEach-Object { $_.ToString() }
    $ErrorActionPreference = $prev
    $joined = (($out | Where-Object { $_ -match 'ERROR|CONTEXT|NOTICE' }) -join ' ') -replace '\s+', ' '
    [pscustomobject]@{ Code = $LASTEXITCODE; Out = $out; Joined = $joined }
}

$script:fail = 0
function Check {
    param([string] $Label, [bool] $Ok, [string] $Detail)
    if (-not $Ok) { $script:fail++ }
    Write-Host ("  {0}  {1}" -f $(if ($Ok) { 'PASS' } else { '*** FAIL ***' }), $Label)
    if ($Detail) { Write-Host ("        {0}" -f $Detail) }
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
        Write-Host ("  SETUP FAILED: " + (($r.Out | Where-Object { $_ -match 'ERROR' }) -join ' ')); return $false
    }
    return $true
}

$undoText = Get-Content -Raw (Join-Path -Path $repoRoot -ChildPath $Undo)

# The counts the post-condition asserts, read back the same way it reads them.
$countsSql = @"
SELECT 'tables='   || count(*) FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history';
SELECT 'enums='    || count(DISTINCT t.typname) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
  JOIN pg_enum e ON e.enumtypid=t.oid WHERE n.nspname='public';
SELECT 'checks='   || count(*) FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND con.contype='c'
  AND c.relname <> 'flyway_schema_history';
SELECT 'fks='      || count(*) FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND con.contype='f'
  AND c.relname <> 'flyway_schema_history';
SELECT 'pks='      || count(*) FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND con.contype='p'
  AND c.relname <> 'flyway_schema_history';
SELECT 'indexes='  || count(*) FROM pg_indexes WHERE schemaname='public' AND tablename <> 'flyway_schema_history';
SELECT 'uniqidx='  || count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND i.indisunique
  AND c.relname <> 'flyway_schema_history';
SELECT 'triggers=' || count(*) FROM pg_trigger tg JOIN pg_class c ON c.oid=tg.tgrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT tg.tgisinternal
  AND c.relname <> 'flyway_schema_history';
SELECT 'composites=' || count(*) FROM pg_constraint
 WHERE conrelid='public.appointments'::regclass AND contype='f' AND conname LIKE '%_tenant_fkey';
SELECT 'singles=' || count(*) FROM pg_constraint WHERE conrelid='public.appointments'::regclass
  AND contype='f' AND conname IN ('appointments_createdById_fkey','appointments_customerId_fkey',
                                  'appointments_doctorId_fkey','appointments_procedureId_fkey');
"@

function Get-State { param([string] $Db) Invoke-Psql -Sql $countsSql -Db $Db }

# =====================================================================
Write-Host '=== A. UNARMED: nothing is touched ==='
if (New-Migrated 'p35_undo_a_check') {
    $r = Invoke-Psql -Sql $undoText -Db 'p35_undo_a_check'
    Check -Label 'unarmed run exits 0' -Ok ($r.Code -eq 0) -Detail $r.Joined
    $s = Get-State 'p35_undo_a_check'
    $c = ($s.Out | Where-Object { $_ -match '^composites=' })
    $k = ($s.Out | Where-Object { $_ -match '^fks=' })
    Check -Label 'all 4 composite FKs still present (nothing was dropped)' -Ok ($c -eq 'composites=4' -and $k -eq 'fks=227') -Detail "$c / $k"
}

Write-Host ''
Write-Host '=== B. ARMED on a clean database: the undo succeeds and returns the post-Phase-14 state ==='
if (New-Migrated 'p35_undo_b_check') {
    $before = Get-State 'p35_undo_b_check'
    $r = Invoke-Psql -Sql $undoText -Db 'p35_undo_b_check' -Vars @{ 'firstelement_undo_armed' = 'on' }
    Check -Label 'armed run exits 0' -Ok ($r.Code -eq 0) -Detail $r.Joined
    Check -Label 'reports PHASE35_REMOVED' -Ok ($r.Joined -match 'PHASE35_REMOVED') -Detail ''

    $after = Get-State 'p35_undo_b_check'
    $want = @{ 'tables='='98'; 'enums='='57'; 'checks='='422'; 'fks='='223'; 'pks='='98';
               'indexes='='394'; 'uniqidx='='205'; 'triggers='='11';
               'composites='='0'; 'singles='='4' }
    foreach ($k in $want.Keys) {
        $got = ($after.Out | Where-Object { $_ -match ('^' + [regex]::Escape($k)) })
        Check -Label ("{0} -> {1} (expected {2})" -f $k, $got, $want[$k]) -Ok ($got -eq ($k + $want[$k]))
    }

    # The pre-existing objects must have SURVIVED the undo.
    $surv = Invoke-Psql -Db 'p35_undo_b_check' -Sql @"
SELECT 'users_idx=' || count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname='users_id_organizationId_key';
SELECT 'cust_idx=' || count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname='customers_id_organizationId_key';
"@
    $u = ($surv.Out | Where-Object { $_ -match '^users_idx=' })
    $cu = ($surv.Out | Where-Object { $_ -match '^cust_idx=' })
    Check -Label 'pre-existing users_id_organizationId_key survived' -Ok ($u -eq 'users_idx=1') -Detail $u
    Check -Label 'pre-existing customers_id_organizationId_key survived' -Ok ($cu -eq 'cust_idx=1') -Detail $cu

    # The fingerprint, read through the SAME tool that produced the recorded
    # checkpoint value: run-verification.ps1 writes V1's output to
    # V1_schema_fingerprint.txt, and the MD5 is taken over that file's non-blank
    # lines joined with \n. verify-clean-bootstrap.ps1 does exactly the same.
    #
    # THIS MATTERS MORE THAN IT LOOKS. An earlier draft of this rehearsal ran
    # V1 through its OWN Invoke-Psql, which uses psql -t -A (raw, unaligned,
    # 2721 lines) and produced 96a1a9dc05d919d3f92c3f56f75fadd7. run-verification
    # uses -P 'fieldsep=|' WITHOUT -t, so its file carries a header and psql's
    # column padding (2724 lines) and hashes to bf31e9f102be19a6c373eb353b2d2131.
    # The SCHEMA was identical in both cases: the fingerprint is a function of
    # the OUTPUT FORMAT as well as of the schema, so comparing a value recorded
    # by one tool against a value produced by another is meaningless. Every
    # recorded fingerprint in expected-state.json came from the bundle path, so
    # that is the path used here.
    $bundle = Join-Path -Path $repoRoot -ChildPath 'ops/flyway/verification/run-verification.ps1'
    $outDir = Join-Path -Path $repoRoot -ChildPath 'evidence/phase-35/undo-fingerprint'
    & $bundle -Container $Container -Database 'p35_undo_b_check' -DbUser $DbUser -OutputDir $outDir | Out-Null
    $fpFile = Join-Path $outDir 'V1_schema_fingerprint.txt'
    if (-not (Test-Path $fpFile)) {
        Check -Label 'the bundle produced a V1 fingerprint file' -Ok $false -Detail "missing $fpFile"
    } else {
        $rows = Get-Content $fpFile | Where-Object { $_ -match '\S' }
        $md5  = [System.Security.Cryptography.MD5]::Create().
                ComputeHash([System.Text.Encoding]::UTF8.GetBytes(($rows -join "`n")))
        $digest = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
        Check -Label 'fingerprint returns to the post-Phase-14 value bf31e9f102be19a6c373eb353b2d2131' `
               -Ok ($digest -eq 'bf31e9f102be19a6c373eb353b2d2131') `
               -Detail ("got $digest from " + $rows.Count + " V1 line(s) via run-verification.ps1")
    }
}

Write-Host ''
Write-Host '=== C. ARMED with a CROSS-TENANT appointment: REFUSED, nothing removed ==='
if (New-Migrated 'p35_undo_c_check') {
    # Step 1: the tenants, projects and doctors. Separate call, so a failure is
    # attributable.
    $base = Invoke-Psql -Db 'p35_undo_c_check' -Sql @"
INSERT INTO public.organizations (id, name, slug, "createdAt", "updatedAt") VALUES
 ('95000000-0000-0000-0000-0000000000a1','undo tenant A','undo-a', now(), now()),
 ('95000000-0000-0000-0000-0000000000b1','undo tenant B','undo-b', now(), now());
INSERT INTO public.projects (id, "organizationId", name, slug, "createdAt", "updatedAt") VALUES
 ('95000000-0000-0000-0000-0000000000a2','95000000-0000-0000-0000-0000000000a1','undo proj A','undo-pa', now(), now()),
 ('95000000-0000-0000-0000-0000000000b2','95000000-0000-0000-0000-0000000000b1','undo proj B','undo-pb', now(), now());
INSERT INTO public.doctors (id, "organizationId", "projectId", name, specialty, "updatedAt") VALUES
 ('95000000-0000-0000-0000-0000000000a3','95000000-0000-0000-0000-0000000000a1','95000000-0000-0000-0000-0000000000a2','undo doc A','General', now()),
 ('95000000-0000-0000-0000-0000000000b3','95000000-0000-0000-0000-0000000000b1','95000000-0000-0000-0000-0000000000b2','undo doc B','General', now());
"@
    # Step 2: the composite is LEFT IN PLACE and the row is inserted with
    # session_replication_role = replica, which suspends trigger firing for the
    # duration. These must be SEPARATE psql invocations: with ON_ERROR_STOP a
    # single -f - that hit the FK rolled the whole script back, so the row
    # vanished along with the ALTER and the rehearsal never had a cross-tenant
    # appointment to refuse.
    #
    $ins  = Invoke-Psql -Db 'p35_undo_c_check' -Sql @"
INSERT INTO public.appointments (id, "organizationId", "projectId", title, "appointmentDate", status, "doctorId", "updatedAt")
SELECT '95000000-0000-0000-0000-0000000000c1', da."organizationId", pa."id", 'undo cross tenant',
       now(), 'PENDING', db2."id", now()
FROM public.doctors da, public.doctors db2, public.projects pa
WHERE da."name"='undo doc A' AND db2."name"='undo doc B' AND pa."name"='undo proj A';
"@
    # Step 3: the composite cannot be re-added over the violating row -- and
    # that refusal is CORRECT, it is the constraint doing its job. So this
    # database cannot be built by "apply then re-add". It is built the way such a
    # database actually arises: the constraint is present, and the cross-tenant
    # row got in some other way (a restored dump, a disabled trigger, a
    # pre-migration write). The rehearsal models that by disabling the
    # constraint's enforcement just long enough to insert, then enabling it
    # again -- which leaves BOTH the row and all four composite FKs in place.
    $ins = Invoke-Psql -Db 'p35_undo_c_check' -Sql @"
SET session_replication_role = replica;
INSERT INTO public.appointments (id, "organizationId", "projectId", title, "appointmentDate", status, "doctorId", "updatedAt")
SELECT '95000000-0000-0000-0000-0000000000c1', da."organizationId", pa."id", 'undo cross tenant',
       now(), 'PENDING', db2."id", now()
FROM public.doctors da, public.doctors db2, public.projects pa
WHERE da."name"='undo doc A' AND db2."name"='undo doc B' AND pa."name"='undo proj A';
SET session_replication_role = origin;
"@
    $present = Invoke-Psql -Db 'p35_undo_c_check' -Sql @"
SELECT 'cross=' || count(*) FROM public.appointments a JOIN public.doctors d ON d."id"=a."doctorId"
 WHERE d."organizationId" <> a."organizationId";
SELECT 'composites=' || count(*) FROM pg_constraint
 WHERE conrelid='public.appointments'::regclass AND contype='f' AND conname LIKE '%_tenant_fkey';
"@
    $cross = ($present.Out | Where-Object { $_ -match '^cross=' })
    $comp  = ($present.Out | Where-Object { $_ -match '^composites=' })
    $seeded = ($base.Code -eq 0 -and $ins.Code -eq 0 -and $cross -eq 'cross=1' -and $comp -eq 'composites=4')
    Check -Label 'a cross-tenant appointment EXISTS alongside all 4 composite FKs' -Ok $seeded `
           -Detail ("base=$($base.Code) ins=$($ins.Code) $cross $comp")
    if (-not $seeded) {
        $present.Out | Where-Object { $_ -match 'ERROR' } | ForEach-Object { Write-Host "        $_" }
    } else {
        $r = Invoke-Psql -Sql $undoText -Db 'p35_undo_c_check' -Vars @{ 'firstelement_undo_armed' = 'on' }
        Check -Label 'armed run REFUSES (non-zero exit)' -Ok ($r.Code -ne 0) -Detail $r.Joined
        Check -Label "reports PHASE35_UNDO_REFUSED" -Ok ($r.Joined -match 'PHASE35_UNDO_REFUSED') -Detail ''
        Check -Label 'names the offending appointment id' -Ok ($r.Joined -match '95000000-0000-0000-0000-0000000000c1') -Detail ''

        $s = Get-State 'p35_undo_c_check'
        $c = ($s.Out | Where-Object { $_ -match '^composites=' })
        $k = ($s.Out | Where-Object { $_ -match '^fks=' })
        Check -Label 'NOTHING was removed: all 4 composite FKs survive the refusal' `
               -Ok ($c -eq 'composites=4' -and $k -eq 'fks=227') -Detail "$c / $k"
        $rows = Invoke-Psql -Db 'p35_undo_c_check' -Sql "SELECT 'rows=' || count(*) FROM public.appointments;"
        $n = ($rows.Out | Where-Object { $_ -match '^rows=' })
        Check -Label 'the cross-tenant row was NOT deleted (refuse, do not repair)' -Ok ($n -eq 'rows=1') -Detail $n
    }
}

Write-Host ''
Write-Host '=== D. PARTIAL batch: REFUSED rather than compounded ==='
if (New-Migrated 'p35_undo_d_check') {
    Invoke-Psql -Db 'p35_undo_d_check' -Sql 'DROP INDEX public."doctors_id_organizationId_key";' | Out-Null
    # that DROP fails while the FK needs it, so drop the constraint first
    Invoke-Psql -Db 'p35_undo_d_check' -Sql @"
ALTER TABLE public.appointments DROP CONSTRAINT appointments_doctor_tenant_fkey;
DROP INDEX IF EXISTS public."doctors_id_organizationId_key";
"@ | Out-Null
    $r = Invoke-Psql -Sql $undoText -Db 'p35_undo_d_check' -Vars @{ 'firstelement_undo_armed' = 'on' }
    Check -Label 'partial batch REFUSES (non-zero exit)' -Ok ($r.Code -ne 0) -Detail $r.Joined
    Check -Label 'reports PHASE35_UNDO_PARTIAL' -Ok ($r.Joined -match 'PHASE35_UNDO_PARTIAL') -Detail ''
    $s = Get-State 'p35_undo_d_check'
    $c = ($s.Out | Where-Object { $_ -match '^composites=' })
    Check -Label 'the surviving composite FKs were NOT removed' -Ok ($c -eq 'composites=3') -Detail $c
}

$dbs = @('p35_undo_a_check','p35_undo_b_check','p35_undo_c_check','p35_undo_d_check')
Write-Host ''
if (-not $KeepControlDatabases) {
    Write-Host 'disposing undo-rehearsal databases'
    foreach ($n in $dbs) {
        Invoke-Psql -Db $DbUser -Sql @"
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$n' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$n";
"@ | Out-Null
    }
    Write-Host 'all undo-rehearsal databases dropped'
} else {
    Write-Host ('-KeepControlDatabases: left in place -- ' + ($dbs -join ', '))
}

Write-Host ''
if ($fail -gt 0) { Write-Host "RESULT: $fail check(s) did not match." -ForegroundColor Red; exit 1 }
Write-Host 'RESULT: every undo outcome matched.' -ForegroundColor Green
exit 0