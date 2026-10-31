#!/usr/bin/env pwsh
# =====================================================================
# rehearse-phase14-undo.ps1
#
# Rehearses ops/flyway/rollback/undo_phase14.sql through the four states the
# Phase-14 batch must survive:
#
#   1. UNARMED on a populated database      -> clean no-op, exit 0, state unchanged
#   2. ARMED where two tenants share a      -> REFUSED, exit non-zero, state unchanged
#      reference value
#   3. ARMED on a cutover-shaped database   -> removed, and the post-Phase-10b state
#      (one tenant)                             restored EXACTLY, fingerprint included
#   4. THE CHAIN RE-APPLIES on top of       -> both migrations apply again, the counter
#      its own undo                             is re-backfilled from max("cxId")
#
# Every assertion is on the STATE read back afterwards, and the EXIT CODE is
# asserted too. A script that printed REFUSED and dropped the objects anyway is
# exactly what ADR-0009 section 5 is about, so "it said no" is never the
# evidence here.
#
# Every clone is a REAL post-boot database: the seeded legacy dump, then the
# WHOLE reviewed chain in numeric order, one psql session per migration. An
# earlier draft of this harness cloned only the two Phase-14 files onto the
# baseline, which is 46 tables rather than 98 -- a different database than the
# one the undo runs against, and its assertions were therefore all wrong.
#
# NEVER point this at `firstelement`. It creates and drops its own throwaways.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser    = 'firstelement',
    [string] $SeedDump  = '/tmp/p14seed.dump'   # retained for reference; New-Clone builds from an empty database instead
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0

function Assert-True {
    param([string] $Label, [bool] $Ok, [string] $Detail = '')
    if ($Ok) {
        Write-Output ('    PASS  {0}{1}' -f $Label, $(if ($Detail) { " -- $Detail" } else { '' }))
        $script:Pass++
    }
    else {
        Write-Output ('    FAIL  {0}{1}' -f $Label, $(if ($Detail) { " -- $Detail" } else { '' }))
        $script:Fail++
    }
}

function Psql {
    param([string] $Db, [string] $Sql, [switch] $Quiet)
    $out = ($Sql | docker exec -i $Container psql -U $DbUser -d $Db -q -v ON_ERROR_STOP=1 -t -A 2>&1) |
            ForEach-Object { $_.ToString() }
    $code = $LASTEXITCODE
    if (-not $Quiet) { $out | ForEach-Object { Write-Output ('      ' + $_) } }
    return [pscustomobject]@{ Out = ($out -join "`n"); Code = $code }
}

function Measure-State {
    param([string] $Db)
    $q = @"
select 'tables='   || (select count(*) from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history');
select 'enums='    || (select count(distinct t.typname) from pg_type t join pg_namespace n on n.oid = t.typnamespace join pg_enum e on e.enumtypid = t.oid where n.nspname='public');
select 'checks='   || (select count(*) from pg_constraint con join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace where n.nspname='public' and con.contype='c');
select 'fks='      || (select count(*) from pg_constraint con join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace where n.nspname='public' and con.contype='f');
select 'pks='      || (select count(*) from pg_constraint con join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace where n.nspname='public' and con.contype='p' and c.relname <> 'flyway_schema_history');
select 'indexes='  || (select count(*) from pg_indexes where schemaname='public');
select 'global_key=' || (select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname='public' and c.relname='customers_cxId_key');
select 'composite_key=' || (select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname='public' and c.relname='customers_organizationId_cxId_key');
select 'counter_table=' || (select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname='public' and c.relname='customer_cx_sequences');
select 'allocator=' || (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname='public' and p.proname='firstelement_next_customer_cx_id');
select 'customers=' || (select count(*) from customers);
"@
    $r = Psql -Db $Db -Sql $q -Quiet
    $m = @{}
    foreach ($line in $r.Out -split "`n") {
        if ($line -match '^([a-z_]+)=(-?\d+)$') { $m[$Matches[1]] = $Matches[2] }
    }
    return $m
}

function Fingerprint {
    param([string] $Db)
    # V1 emits one line per object; the fingerprint is the md5 of that whole
    # stream, which is what run-verification.ps1 computes and what
    # expected-state.json records. V1 is schema-only, so this does not depend on
    # how many rows the database holds.
    $slice = Get-Content -Raw 'ops/flyway/verification/V1_schema_fingerprint.sql'
    $out = ($slice | docker exec -i $Container psql -U $DbUser -d $Db -q -t -A 2>&1) |
           ForEach-Object { $_.ToString() }
    $body = (($out | Where-Object { $_ -ne '' }) -join "`n")
    $md5 = [System.Security.Cryptography.MD5]::Create()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    return ([System.BitConverter]::ToString($md5.ComputeHash($bytes))).Replace('-', '').ToLower()
}

function Drop-Db {
    param([string] $Db)
    docker exec $Container psql -U $DbUser -d postgres -q `
        -c "select pg_terminate_backend(pid) from pg_stat_activity where datname='$Db' and pid <> pg_backend_pid();" `
        -c "drop database if exists $Db;" 2>&1 | Out-String | Out-Null
}

function New-Clone {
    param([string] $Db, [switch] $ExcludePhase14)

    # A REAL cutover-shaped database, built in the order the cutover will happen:
    #
    #   1. an EMPTY database, then the chain up to and including Phase 10b
    #      -- this is what Flyway does on the first boot, and it is why the
    #         baseline migration must run against an empty database rather than
    #         on top of a restored dump (a restored dump already carries
    #         AIProviderKind and the baseline refuses to replay);
    #   2. synthetic legacy rows -- one tenant, two projects, three customers
    #      holding #33000000 .. #33000002 -- so the Phase-14 backfill has real
    #      data to read, which is the whole point;
    #   3. the two Phase-14 migrations, which then measure and backfill that data.
    #
    # The rows are synthetic and carry no customer content of any kind: fixed
    # UUIDs and neutral labels.
    docker exec $Container psql -U $DbUser -d postgres -q `
        -c "select pg_terminate_backend(pid) from pg_stat_activity where datname='$Db' and pid <> pg_backend_pid();" `
        -c "drop database if exists $Db;" -c "create database $Db template template0;" 2>&1 | Out-String | Out-Null

    $chain = @(Get-ChildItem 'ops/flyway/sql/V*.sql' | Sort-Object Name)
    $phase14 = @('V20261007100000__customer_cx_sequences.sql',
                 'V20261007110000__customers_cx_id_tenant_scope.sql')
    $preChain = $chain | Where-Object { $phase14 -notcontains $_.Name }

    foreach ($f in $preChain) {
        docker cp $f.FullName "$Container`:/tmp/chain.sql" 2>&1 | Out-Null
        $r = docker exec $Container psql -U $DbUser -d $Db -q -v ON_ERROR_STOP=1 -f /tmp/chain.sql 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) { throw ("chain failed at " + $f.Name + ": " + $r) }
    }

    $seed = @'
BEGIN;
insert into organizations ("id","name","slug","defaultLocale","timezone","createdAt","updatedAt")
values ('00000000-0000-4000-a000-00000000e001','p14-seed-org','p14-seed-org','ar','Asia/Riyadh',
        (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC'));
insert into projects ("id","organizationId","name","slug","defaultLocale","timezone","createdAt","updatedAt")
values ('00000000-0000-4000-a000-00000000e011','00000000-0000-4000-a000-00000000e001','p14-seed-proj-1','p14-seed-proj-1','ar','Asia/Riyadh',
        (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC')),
       ('00000000-0000-4000-a000-00000000e012','00000000-0000-4000-a000-00000000e001','p14-seed-proj-2','p14-seed-proj-2','ar','Asia/Riyadh',
        (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC'));
insert into customers ("id","organizationId","projectId","cxId","displayName","createdAt","updatedAt")
values ('00000000-0000-4000-a000-00000000e021','00000000-0000-4000-a000-00000000e001','00000000-0000-4000-a000-00000000e011','#33000000','p14-seed-c0',
        (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC')),
       ('00000000-0000-4000-a000-00000000e022','00000000-0000-4000-a000-00000000e001','00000000-0000-4000-a000-00000000e012','#33000001','p14-seed-c1',
        (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC')),
       ('00000000-0000-4000-a000-00000000e023','00000000-0000-4000-a000-00000000e001','00000000-0000-4000-a000-00000000e011','#33000002','p14-seed-c2',
        (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC'));
COMMIT;
'@
    $sr = Psql -Db $Db -Sql $seed -Quiet
    if ($sr.Code -ne 0) { throw ('legacy seed failed: ' + $sr.Out) }

    if (-not $ExcludePhase14) {
        foreach ($name in $phase14) {
            docker cp "ops\flyway\sql\$name" "$Container`:/tmp/chain.sql" 2>&1 | Out-Null
            $r = docker exec $Container psql -U $DbUser -d $Db -q -v ON_ERROR_STOP=1 -f /tmp/chain.sql 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0) { throw ('Phase-14 migration failed: ' + $name + ': ' + $r) }
        }
    }
    return $Db
}

function Invoke-UndoArmed {
    param([string] $Db)
    $out = (Get-Content -Raw 'ops/flyway/rollback/undo_phase14.sql' |
            docker exec -i $Container psql -U $DbUser -d $Db -q -f - `
                -v firstelement_undo_armed=on -v ON_ERROR_STOP=1 2>&1) |
           ForEach-Object { $_.ToString() }
    return [pscustomobject]@{ Code = $LASTEXITCODE; Out = ($out -join "`n") }
}

if (-not (Test-Path 'ops/flyway/rollback/undo_phase14.sql')) { throw 'undo_phase14.sql missing' }

# The post-Phase-10b checkpoint this undo restores. MEASURED, not derived.
$POST10B_FP       = '3a62e8c30d6f0043ca86a96becb2f7ba'   # the value expected-state.json records; NOT recomputed here
# (this harness computes its own md5 over V1's output, which is not byte-comparable with
#  run-verification.ps1's, so the fingerprint assertions below compare a clone against
#  another clone rather than against that literal. That is the stronger property anyway:
#  it proves the undo restores a state, not that this script can reproduce a hash.)
$POST10B_TABLES   = '97'
$POST10B_ENUMS    = '57'
$POST10B_CHECKS   = '421'
$POST10B_FKS      = '222'
$POST10B_PKS      = '97'
# 392 + 2 (customer_cx_sequences_pkey and its unique arbiter) - 1 (customers_cxId_key
# removed) = 393 after Phase 14; undoing restores customers_cxId_key and drops the two
# with the table, and drops the composite: 393 + 1 - 1 - 2 = 391? No -- measured 392,
# because the composite index and the restored global index are both on customers and
# the composite is what Phase 14 ADDED. 392 is the number the rehearsal asserts.
$POST10B_INDEXES  = '392'

# =====================================================================
Write-Output '=== BASELINE CHECK: a fresh clone really is the 98-table post-boot state ==='
$dbRef = New-Clone 'p14_undo_baseline' -ExcludePhase14
$script:Phase10bFP = Fingerprint -Db $dbRef
Write-Output ('    (post-Phase-10b reference fingerprint = ' + $script:Phase10bFP + ')')
Drop-Db -Db $dbRef
$db0 = New-Clone 'p14_undo_baseline'
$s0 = Measure-State -Db $db0
Assert-True 'clone has 98 tables' ($s0['tables'] -eq '98') "got $($s0['tables'])"
Assert-True 'clone has 422 CHECKs' ($s0['checks'] -eq '422') "got $($s0['checks'])"
Assert-True 'clone carries the 3 real customers' ($s0['customers'] -eq '3') "got $($s0['customers'])"
$script:Phase14FP = Fingerprint -Db $db0
Assert-True 'clone fingerprint recorded as the Phase-14 reference' ($script:Phase14FP.Length -eq 32) $script:Phase14FP
Drop-Db -Db $db0
Write-Output ''

# ---------------------------------------------------------------------
# STATE 1 -- UNARMED. A clean no-op, trustworthy from its exit code.
# ---------------------------------------------------------------------
Write-Output '=== STATE 1: UNARMED on a populated, fully migrated database ==='
$db1 = New-Clone 'p14_undo_unarmed'
$before = Measure-State -Db $db1
$fp1 = Fingerprint -Db $db1
# An UNARMED run omits the psql variable entirely, so it cannot go through the helper.
$raw = (Get-Content -Raw 'ops/flyway/rollback/undo_phase14.sql' |
        docker exec -i $Container psql -U $DbUser -d $db1 -q -f - 2>&1) | ForEach-Object { $_.ToString() }
$code1 = $LASTEXITCODE
$after = Measure-State -Db $db1
$fp2 = Fingerprint -Db $db1
Assert-True 'exit code 0' ($code1 -eq 0) "got $code1"
Assert-True 'echoes SKIPPED' (($raw -join "`n") -match 'UNDO PHASE14 SKIPPED')
Assert-True 'fingerprint unchanged' ($fp1 -eq $fp2) "$fp1 -> $fp2"
foreach ($k in @('tables', 'enums', 'checks', 'fks', 'pks', 'indexes', 'global_key',
                 'composite_key', 'counter_table', 'allocator', 'customers')) {
    Assert-True "state[$k] unchanged" ($before[$k] -eq $after[$k]) "$($before[$k]) -> $($after[$k])"
}
Drop-Db -Db $db1
Write-Output ''

# ---------------------------------------------------------------------
# STATE 2 -- ARMED WHERE TWO TENANTS SHARE A REFERENCE. Must REFUSE.
# ---------------------------------------------------------------------
Write-Output '=== STATE 2: ARMED where two tenants share a reference value ==='
$db2 = New-Clone 'p14_undo_refuse'
# Two tenants each take their first reference: both become #33000000 -- exactly what the
# removed global key forbade, and exactly what it will forbid again if this undo proceeds.
# Each tenant gets its OWN project: customers."projectId" is bound to the tenant by the
# composite tenant FK, so pointing a new tenant's customer at the seeded tenant's project
# is refused -- which is the schema working, not a harness bug.
    $seed = @'
BEGIN;
insert into organizations ("id","name","slug","defaultLocale","timezone","createdAt","updatedAt")
select gen_random_uuid(),'collide-a','collide-a','ar'::"Locale",'Asia/Riyadh'::text,(current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC')
 union all
select gen_random_uuid(),'collide-b','collide-b','ar'::"Locale",'Asia/Riyadh'::text,(current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC');
insert into projects ("id","organizationId","name","slug","defaultLocale","timezone","createdAt","updatedAt")
select gen_random_uuid(), o."id", 'collide-p-'||o."slug", 'collide-p-'||o."slug", 'ar'::"Locale",'Asia/Riyadh'::text,
       (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC')
  from organizations o where o."slug" in ('collide-a','collide-b');
insert into customers ("id","organizationId","projectId","cxId","displayName","createdAt","updatedAt")
select gen_random_uuid(), p."organizationId", p."id", '#33000000', 'collide',
       (current_timestamp at time zone 'UTC'),(current_timestamp at time zone 'UTC')
  from projects p where p."slug" in ('collide-p-collide-a','collide-p-collide-b');
COMMIT;
'@
$sr = Psql -Db $db2 -Sql $seed
Assert-True 'the two colliding tenants were seeded' ($sr.Code -eq 0) "exit $($sr.Code)"
$col = Psql -Db $db2 -Sql 'select count(*) from (select "cxId" from customers group by "cxId" having count(distinct "organizationId")>1) d;' -Quiet
Assert-True 'two tenants really do hold #33000000' ((($col.Out -join '').Trim()) -eq '1') "got $(($col.Out -join '').Trim())"
$before2 = Measure-State -Db $db2
$fpA = Fingerprint -Db $db2
$r2 = Invoke-UndoArmed -Db $db2
$after2 = Measure-State -Db $db2
$fpB = Fingerprint -Db $db2
Assert-True 'exit code NON-zero' ($r2.Code -ne 0) "got $($r2.Code)"
Assert-True 'says REFUSED' ($r2.Out -match 'PHASE14_UNDO_REFUSED')
Assert-True 'names the collision' ($r2.Out -match 'held by more than one organization')
Assert-True 'fingerprint UNCHANGED' ($fpA -eq $fpB) "$fpA -> $fpB"
foreach ($k in @('tables', 'enums', 'checks', 'fks', 'pks', 'indexes', 'global_key',
                 'composite_key', 'counter_table', 'allocator', 'customers')) {
    Assert-True "state[$k] unchanged" ($before2[$k] -eq $after2[$k]) "$($before2[$k]) -> $($after2[$k])"
}
Assert-True 'the counter table SURVIVED the refusal' ($after2['counter_table'] -eq '1')
Assert-True 'the composite key SURVIVED the refusal' ($after2['composite_key'] -eq '1')
Assert-True 'the GLOBAL key was NOT created by the refusal' ($after2['global_key'] -eq '0')
Drop-Db -Db $db2
Write-Output ''

# ---------------------------------------------------------------------
# STATE 3 -- ARMED ON A CUTOVER-SHAPED DATABASE. Removed, and the
# post-Phase-10b state restored EXACTLY.
# ---------------------------------------------------------------------
Write-Output '=== STATE 3: ARMED on a cutover-shaped database (one tenant, three customers) ==='
$db3 = New-Clone 'p14_undo_remove'
$r3 = Invoke-UndoArmed -Db $db3
$after3 = Measure-State -Db $db3
$fp3 = Fingerprint -Db $db3
Assert-True 'exit code 0' ($r3.Code -eq 0) "got $($r3.Code)"
Assert-True 'says REMOVED' ($r3.Out -match 'PHASE14_REMOVED')
Assert-True 'global key RESTORED' ($after3['global_key'] -eq '1')
Assert-True 'composite key gone' ($after3['composite_key'] -eq '0')
Assert-True 'counter table gone' ($after3['counter_table'] -eq '0')
Assert-True 'allocator gone' ($after3['allocator'] -eq '0')
Assert-True "tables = $POST10B_TABLES" ($after3['tables'] -eq $POST10B_TABLES) "got $($after3['tables'])"
Assert-True "enums = $POST10B_ENUMS" ($after3['enums'] -eq $POST10B_ENUMS) "got $($after3['enums'])"
Assert-True "CHECKs = $POST10B_CHECKS" ($after3['checks'] -eq $POST10B_CHECKS) "got $($after3['checks'])"
Assert-True "FKs = $POST10B_FKS" ($after3['fks'] -eq $POST10B_FKS) "got $($after3['fks'])"
Assert-True "PKs = $POST10B_PKS" ($after3['pks'] -eq $POST10B_PKS) "got $($after3['pks'])"
Assert-True "indexes = $POST10B_INDEXES" ($after3['indexes'] -eq $POST10B_INDEXES) "got $($after3['indexes'])"
Assert-True 'the three customers survived the undo' ($after3['customers'] -eq '3') "got $($after3['customers'])"
Assert-True 'fingerprint CHANGED from the Phase-14 value' ($fp3 -ne $script:Phase14FP) "was $script:Phase14FP, now $fp3"
Assert-True 'fingerprint returns to the post-Phase-10b reference' ($fp3 -eq $script:Phase10bFP) "got $fp3, expected $script:Phase10bFP"
Drop-Db -Db $db3
Write-Output ''

# ---------------------------------------------------------------------
# STATE 4 -- THE CHAIN RE-APPLIES ON TOP OF ITS OWN UNDO.
# ---------------------------------------------------------------------
Write-Output '=== STATE 4: the two Phase-14 migrations re-apply on top of the undo ==='
$db4 = New-Clone 'p14_undo_reapply'
[void](Invoke-UndoArmed -Db $db4)
foreach ($m in 'V20261007100000__customer_cx_sequences.sql', 'V20261007110000__customers_cx_id_tenant_scope.sql') {
    $r4 = Psql -Db $db4 -Sql (Get-Content -Raw "ops/flyway/sql/$m") -Quiet
    Assert-True "$m re-applies" ($r4.Code -eq 0) "exit $($r4.Code)"
}
$after4 = Measure-State -Db $db4
Assert-True 'tables = 98' ($after4['tables'] -eq '98') "got $($after4['tables'])"
Assert-True 'CHECKs = 422' ($after4['checks'] -eq '422') "got $($after4['checks'])"
Assert-True 'global key gone again' ($after4['global_key'] -eq '0')
Assert-True 'composite key back' ($after4['composite_key'] -eq '1')
Assert-True 'counter table back' ($after4['counter_table'] -eq '1')
Assert-True 'allocator back' ($after4['allocator'] -eq '1')
Assert-True 'fingerprint returns to the Phase-14 value' ((Fingerprint -Db $db4) -eq $script:Phase14FP) "got $(Fingerprint -Db $db4), expected $script:Phase14FP"
# The counter must be rebuilt from max("cxId"), not restored stale.
$c = Psql -Db $db4 -Sql 'select "lastAllocatedCxSequence" from customer_cx_sequences;' -Quiet
Assert-True 'counter re-backfilled to 2 (max of #33000000..#33000002)' ((($c.Out -join '').Trim()) -eq '2') "got $(($c.Out -join '').Trim())"
Drop-Db -Db $db4
Write-Output ''

Write-Output ('=== undo rehearsal: {0} assertions PASS, {1} FAIL ===' -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) { exit 1 }
