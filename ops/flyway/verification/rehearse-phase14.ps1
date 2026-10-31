#!/usr/bin/env pwsh
# =====================================================================
# Negative controls for V16_customer_cx_sequence_slice.sql
#
# Each control breaks EXACTLY ONE thing on a fully migrated, populated
# database and asserts that V16 goes RED -- and that the error is the
# intended one, not merely that something failed.
#
# Per ADR-0009 section 5 the assertion is on STATE: the slice's exit code and
# the assertion that fired. A control that only proved "some error occurred"
# would pass for the wrong reason, which is the failure mode this project has
# now hit four times.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $Template   = 'firstelement_p14_realdata',
    [string] $Slice      = 'ops/flyway/verification/V16_customer_cx_sequence_slice.sql'
)

$ErrorActionPreference = 'Continue'

function Invoke-Slice {
    param([string] $Db)
    $sliceText = Get-Content -Raw $Slice
    $out = ($sliceText | docker exec -i $Container psql -U firstelement -d $Db `
                -v ON_ERROR_STOP=1 -P pager=off -f - 2>&1) | ForEach-Object { $_.ToString() }
    $code = $LASTEXITCODE
    $err = @($out | Where-Object { $_ -match 'ERROR' } | Select-Object -First 1)[0]
    $clean = [string]($err -replace '\s+', ' ') -replace '^psql:<stdin>:\d+: ', ''
    if ($clean.Length -gt 155) { $clean = $clean.Substring(0, 155) }
    return [pscustomobject]@{ Code = $code; Err = $clean; Full = [string]($err -replace '\s+', ' ') }
}

function New-Break {
    param([string] $Db, [string] $Sql)
    $r = $Sql | docker exec -i $Container psql -U firstelement -d $Db -q -v ON_ERROR_STOP=1 2>&1 | Out-String
    if ($r -match 'ERROR') {
        Write-Output ('      SETUP FAILED: ' + (($r -split "`n" | Where-Object { $_ -match 'ERROR' }) -join ' '))
        return $false
    }
    return $true
}

$controls = @(
    @{ id = 'p14_neg1'; label = 'NEG-1  global unique key RESTORED'
       expect = 'the GLOBAL customers_cxId_key is STILL PRESENT'   # fires 1b; 3a is defence in depth behind it
       sql = @'
DROP INDEX "public"."customers_organizationId_cxId_key";
CREATE UNIQUE INDEX "customers_cxId_key" ON "public"."customers" USING "btree" ("cxId");
'@ },
    @{ id = 'p14_neg2'; label = 'NEG-2  counter CORRUPTED below the live maximum'
       expect = 'sit ABOVE their organization'
       sql = 'UPDATE "public"."customer_cx_sequences" SET "lastAllocatedCxSequence" = 0;' },
    @{ id = 'p14_neg3'; label = 'NEG-3  counter ROW DELETED for an org with customers'
       expect = 'no counter'
       sql = 'DELETE FROM "public"."customer_cx_sequences";' },
    @{ id = 'p14_neg4'; label = 'NEG-4  allocator marked IMMUTABLE'
       expect = 'instead of VOLATILE'
       sql = 'ALTER FUNCTION "public"."firstelement_next_customer_cx_id"(uuid) IMMUTABLE;' },
    @{ id = 'p14_neg5'; label = 'NEG-5  format-ceiling CHECK dropped'
       expect = 'sequence_range_check'
       sql = 'ALTER TABLE "public"."customer_cx_sequences" DROP CONSTRAINT customer_cx_sequences_sequence_range_check;' },
    @{ id = 'p14_neg6'; label = 'NEG-6  composite key made PARTIAL'
       expect = 'PARTIAL'
       sql = @'
DROP INDEX "public"."customers_organizationId_cxId_key";
CREATE UNIQUE INDEX "customers_organizationId_cxId_key"
    ON "public"."customers" USING "btree" ("organizationId", "cxId")
    WHERE "cxId" <> '';
'@ },
    @{ id = 'p14_neg7'; label = 'NEG-7  composite key COLUMNS REVERSED (global in all but name)'
       expect = 'which is not'
       sql = @'
DROP INDEX "public"."customers_organizationId_cxId_key";
CREATE UNIQUE INDEX "customers_organizationId_cxId_key"
    ON "public"."customers" USING "btree" ("cxId", "organizationId");
'@ },
    @{ id = 'p14_neg8'; label = 'NEG-8  counter table DROPPED (migration 2 without migration 1)'
       expect = 'HALF-APPLIED Phase-14 state'
       sql = 'DROP TABLE "public"."customer_cx_sequences" CASCADE;' }
)

# ---------------------------------------------------------------------
# POSITIVE CONTROL FIRST. A negative control is only evidence if the
# unmodified database is green to begin with.
# ---------------------------------------------------------------------
Write-Output '=== POSITIVE CONTROL (unmodified, fully migrated, populated) ==='
$r = Invoke-Slice -Db $Template
Write-Output ('  exit={0}  expect 0' -f $r.Code)
Write-Output ('  {0}' -f $(if ($r.Code -eq 0) { 'no ERROR -- the slice is green before we break anything' } else { 'FAILED BEFORE ANY BREAK: ' + $r.Err }))
Write-Output ''

foreach ($c in $controls) {
    $db = $c.id
    docker exec $Container psql -U firstelement -d postgres -q `
        -c "select pg_terminate_backend(pid) from pg_stat_activity where datname='$db' and pid <> pg_backend_pid();" `
        -c "drop database if exists $db;" -c "create database $db template template0;" 2>&1 | Out-String | Out-Null
    docker exec $Container sh -c "pg_restore -U firstelement -d $db --no-owner --no-privileges /tmp/p14seed.dump" 2>&1 | Out-Null

    # re-apply the two Phase-14 migrations so the clone is genuinely post-boot
    foreach ($m in 'V20261007100000__customer_cx_sequences.sql', 'V20261007110000__customers_cx_id_tenant_scope.sql') {
        docker cp "ops\flyway\sql\$m" "$Container`:/tmp/$m" 2>&1 | Out-Null
        docker exec $Container psql -U firstelement -d $db -q -v ON_ERROR_STOP=1 -f "/tmp/$m" 2>&1 | Out-String | Out-Null
    }

    Write-Output ('=== {0} ===' -f $c.label)
    if (-not (New-Break -Db $db -Sql $c.sql)) { Write-Output ''; continue }

    $r = Invoke-Slice -Db $db
    Write-Output ('  exit={0}  expect NON-zero' -f $r.Code)
    $hit = ($r.Full -like ('*' + $c.expect + '*'))
    Write-Output ('  {0}' -f $r.Err)
    Write-Output ('  intended assertion fired: {0}   (looked for: {1})' -f $hit, $c.expect)
    if ($r.Code -eq 0) { Write-Output '  *** THE SLICE STAYED GREEN -- THE CONTROL IS WORTHLESS ***' }
    Write-Output ''
}

# dispose every throwaway
foreach ($c in $controls) {
    docker exec $Container psql -U firstelement -d postgres -q `
        -c "select pg_terminate_backend(pid) from pg_stat_activity where datname='$($c.id)' and pid <> pg_backend_pid();" `
        -c "drop database if exists $($c.id);" 2>&1 | Out-String | Out-Null
}
Write-Output 'all control databases dropped'
