#!/usr/bin/env pwsh
# =====================================================================
# verify-phase14-fixture.ps1
#
# The Testcontainers fixture (FlywayBaselineAcceptanceTest) could NOT be run on
# this host: the Gradle daemon starts and then the client cannot reach it, and
# `gradlew help` fails identically, so the failure is environmental rather than
# a consequence of any change. gradle.properties is outside this agent's
# ownership, so the fix is not mine to make.
#
# This script therefore verifies, DIRECTLY, every property the fixture asserts
# about the chain -- against a real 24-migration database -- so that the
# orchestrator's `./gradlew build` confirms a state this script has already
# measured rather than discovering it.
# =====================================================================
$ErrorActionPreference = 'Continue'
$Pass = 0; $Fail = 0
function Check { param([string]$L, [bool]$Ok, [string]$D = '')
    if ($Ok) { Write-Output ("  PASS  {0}{1}" -f $L, $(if($D){" -- $D"}else{''})); $script:Pass++ }
    else     { Write-Output ("  FAIL  {0}{1}" -f $L, $(if($D){" -- $D"}else{''})); $script:Fail++ } }

$DB = 'firstelement_p14_chain'
function Q { param([string]$Sql)
    $out = ($Sql | docker exec -i firstelement-postgres psql -U firstelement -d $DB -q -t -A -v ON_ERROR_STOP=1 2>&1) |
           ForEach-Object { $_.ToString() }
    return (($out | Select-Object -First 1) -replace '\s+', ' ').Trim()
}

Write-Output '=== A. CHAIN_FILES completeness -- the check that catches a migration nobody reviewed ==='
$java = Get-Content -Raw 'backend/database/src/test/java/com/firstelement/db/FlywayBaselineAcceptanceTest.java'
$block = [regex]::Match($java, 'CHAIN_FILES = List\.of\((?<body>.*?)\);', 'Singleline').Groups['body'].Value
$named = [regex]::Matches($block, '"(V[^"]+\.sql)"') | ForEach-Object { $_.Groups[1].Value }
$onDisk = Get-ChildItem 'ops/flyway/sql/V*.sql' | Select-Object -ExpandProperty Name
$packaged = Get-ChildItem 'backend/database/src/main/resources/db/migration/V*.sql' | Select-Object -ExpandProperty Name
Check 'CHAIN_FILES names 24 files' ($named.Count -eq 24) "got $($named.Count)"
Check 'ops/flyway/sql holds 24 files' ($onDisk.Count -eq 24) "got $($onDisk.Count)"
Check 'the packaged copy holds 24 files' ($packaged.Count -eq 24) "got $($packaged.Count)"
$missing = $onDisk | Where-Object { $named -notcontains $_ }
Check 'ops/flyway/sql contains nothing CHAIN_FILES does not name' ($missing.Count -eq 0) ($missing -join ', ')
$absent = $named | Where-Object { $onDisk -notcontains $_ }
Check 'CHAIN_FILES names nothing that is absent from ops/flyway/sql' ($absent.Count -eq 0) ($absent -join ', ')
Check 'CHAIN_FILES is in ascending version order' (($named -join '|') -eq (($named | Sort-Object) -join '|'))
Check 'every file is 14-digit versioned' ((($named | Where-Object { $_ -notmatch '^V\d{14}__' }).Count) -eq 0)

Write-Output ''
Write-Output '=== B. Byte-identity across both copies, all 24 ==='
$drift = @()
foreach ($f in $onDisk) {
    $a = (Get-FileHash "ops/flyway/sql/$f" -Algorithm SHA256).Hash
    $b = (Get-FileHash "backend/database/src/main/resources/db/migration/$f" -Algorithm SHA256).Hash
    if ($a -ne $b) { $drift += $f }
}
Check 'all 24 files byte-identical in both copies' ($drift.Count -eq 0) ($drift -join ', ')

Write-Output ''
Write-Output '=== C. No .conf sidecar anywhere (fixture asserts this) ==='
$sidecars = @(Get-ChildItem -Recurse -Include '*.conf' -Path 'ops/flyway/sql', 'backend/database/src/main/resources/db/migration' -ErrorAction SilentlyContinue)
Check 'zero .conf sidecars' ($sidecars.Count -eq 0) "$($sidecars.Count) found"

Write-Output ''
Write-Output "=== D. The count assertions, measured on the real 24-migration database ($DB) ==="
$t = Q 'select count(*) from pg_tables where schemaname=''public'' and tablename <> ''flyway_schema_history'''
Check 'tables = 98' ($t -eq '98') "got $t"
$e = Q 'select count(distinct t.typname) from pg_type t join pg_namespace n on n.oid=t.typnamespace join pg_enum g on g.enumtypid=t.oid where n.nspname=''public'''
Check 'enums = 57' ($e -eq '57') "got $e"
$ck = Q 'select count(*) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=''public'' and con.contype=''c'''
Check 'CHECK constraints = 422' ($ck -eq '422') "got $ck"
$fk = Q 'select count(*) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=''public'' and con.contype=''f'''
Check 'foreign keys = 223' ($fk -eq '223') "got $fk"
$pk = Q 'select count(*) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=''public'' and con.contype=''p'' and c.relname <> ''flyway_schema_history'''
Check 'primary keys = 98' ($pk -eq '98') "got $pk"
$uc = Q 'select count(*) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=''public'' and con.contype=''u'''
Check 'unique CONSTRAINTS = 0 (V4 invariant)' ($uc -eq '0') "got $uc"
$tl = Q 'select count(*) from pg_indexes where schemaname=''public'' and tablename <> ''flyway_schema_history'' and indexdef like ''%USING btree ("organizationId"%'''
Check 'tenant-led indexes = 209' ($tl -eq '209') "got $tl"
$ix = Q 'select count(*) from pg_indexes where schemaname=''public'''
Check 'indexes = 394' ($ix -eq '394') "got $ix"
$ux = Q 'select count(*) from pg_indexes where schemaname=''public'' and indexdef like ''CREATE UNIQUE%'''
Check 'unique indexes = 205' ($ux -eq '205') "got $ux"

Write-Output ''
Write-Output '=== E. The Phase-14 objects the new assertions describe ==='
Check 'customer_cx_sequences exists' ((Q 'select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname=''public'' and c.relname=''customer_cx_sequences''') -eq '1')
Check 'customers_cxId_key is GONE' ((Q 'select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname=''public'' and c.relname=''customers_cxId_key''') -eq '0')
Check 'customers_organizationId_cxId_key exists' ((Q 'select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname=''public'' and c.relname=''customers_organizationId_cxId_key''') -eq '1')
Check 'the composite key is UNIQUE on ("organizationId","cxId")' ((Q 'select indexdef from pg_indexes where schemaname=''public'' and indexname=''customers_organizationId_cxId_key''') -eq 'CREATE UNIQUE INDEX "customers_organizationId_cxId_key" ON public.customers USING btree ("organizationId", "cxId")')
Check 'the allocator exists, VOLATILE, one argument' ((Q 'select provolatile from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname=''public'' and p.proname=''firstelement_next_customer_cx_id'' and p.pronargs=1') -eq 'v')
Check 'the format-ceiling CHECK exists' ((Q 'select count(*) from pg_constraint where conname=''customer_cx_sequences_sequence_range_check''') -eq '1')
Check 'the ON CONFLICT arbiter is on "organizationId" alone' ((Q 'select indexdef from pg_indexes where schemaname=''public'' and indexname=''customer_cx_sequences_organizationId_key''') -eq 'CREATE UNIQUE INDEX "customer_cx_sequences_organizationId_key" ON public.customer_cx_sequences USING btree ("organizationId")')
Check 'no timestamptz on the counter table' ((Q 'select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_attribute a on a.attrelid=c.oid and a.atttypid=''timestamptz''::regtype where n.nspname=''public'' and c.relname=''customer_cx_sequences''') -eq '0')

Write-Output ''
Write-Output ('=== {0} checks PASS, {1} FAIL ===' -f $Pass, $Fail)
if ($Fail -gt 0) { exit 1 }