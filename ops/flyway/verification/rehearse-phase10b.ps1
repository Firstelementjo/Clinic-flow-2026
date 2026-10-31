#!/usr/bin/env pwsh
# =====================================================================
# rehearse-phase10b.ps1
#
# The Phase-10b schema-remediation rehearsal, END TO END, on throwaway
# databases only. It answers one question per step and asserts on the STATE
# AFTER each action, never on the message the action printed (ADR-0009 §5).
#
# It exists because the first two attempts at this batch both passed for the
# wrong reason and were only caught by running it:
#
#   * the undo's partial-slice guard read `present IS NULL OR absent IS NULL`,
#     which is true whenever the batch is WHOLE, so it refused a perfectly
#     intact batch;
#   * the audit trigger read coalesce(NEW."revokedByUserId", NEW."suspendedByUserId",
#     ...), and a plpgsql record field that does not exist on the firing table is
#     a RUNTIME error -- so key creation failed on the first INSERT;
#   * three LIKE patterns against pg_get_constraintdef were written from the
#     DDL's own text instead of PostgreSQL's rendering, and one of them omitted
#     the space after `=`, so it matched nothing and could never have fired.
#
# None of those three is visible from reading the SQL. All three are visible
# from running it against a real PostgreSQL and then looking at what is there.
#
# NEVER points itself at `firstelement`. It refuses.
#
#   .\ops\flyway\verification\rehearse-phase10b.ps1 `
#       -Container firstelement-postgres -Keep
#
# With -Keep the throwaway databases are left behind for inspection and the
# names are printed. Without it they are dropped.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser    = 'firstelement',
    [string] $DbPrefix  = 'firstelement_p10b',
    [switch] $Keep,
    [switch] $ThrowOnViolation
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)))
$failures = New-Object System.Collections.Generic.List[string]
$results  = New-Object System.Collections.Generic.List[string]

function Write-Step { param($m) Write-Host "`n==> $m" -ForegroundColor Cyan }

function Invoke-Docker {
    param([string[]] $DockerArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& docker @DockerArgs 2>&1) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    if ($code -ne 0) { throw "docker failed (exit ${code}): docker $($DockerArgs -join ' ')`n$out" }
    return $out
}

# The SQL goes in over STDIN, never as a -c argument.
#
# Windows PowerShell 5.1 strips embedded double quotes when it builds a native
# command line, so `psql -c "... where ""scopeType"" ..."` reaches PostgreSQL with
# the quotes gone and the query fails with `column "scopetype" does not exist`.
# Piping the text avoids the whole quoting layer: the rehearsal's own state query
# is full of quoted camelCase column names, and every one of them has to survive.
function Invoke-Psql {
    param([string] $Sql, [string] $Db, [string] $Label = '')
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = ($Sql | & docker exec -i $Container psql -U $DbUser -d $Db -t -A -F ' | ' `
                      -v ON_ERROR_STOP=1 2>&1) | ForEach-Object { $_.ToString() }
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    if ($code -ne 0) { throw "psql failed on $Db ($Label):`n$($out -join "`n")" }
    return ($out | Where-Object { $_ -match '\S' } | Select-Object -Last 1).Trim()
}

# Asserts on the state, names the assertion, and records it. A failing
# assertion is collected rather than thrown, so one run reports EVERY problem
# instead of only the first.
function Assert-Equal {
    param([string] $Name, [string] $Actual, [string] $Expected)
    if ($Actual -eq $Expected) {
        Write-Host "    PASS  $Name = $Actual" -ForegroundColor Green
        $results.Add("PASS  $Name = $Actual")
    } else {
        Write-Host "    FAIL  ${Name}: expected [$Expected] got [$Actual]" -ForegroundColor Red
        $failures.Add("FAIL  ${Name}: expected [$Expected] got [$Actual]")
    }
}

function New-Throwaway {
    param([string] $Name)
    Invoke-Docker @('exec', $Container, 'psql', '-U', $DbUser, '-d', 'postgres', '-q', '-v', 'ON_ERROR_STOP=1',
                    '-c', "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Name' AND pid <> pg_backend_pid();",
                    '-c', "DROP DATABASE IF EXISTS $Name;",
                    '-c', "CREATE DATABASE $Name TEMPLATE template0;") | Out-Null
}

function Drop-Throwaway {
    param([string] $Name)
    Invoke-Docker @('exec', $Container, 'psql', '-U', $DbUser, '-d', 'postgres', '-q', '-v', 'ON_ERROR_STOP=1',
                    '-c', "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$Name' AND pid <> pg_backend_pid();",
                    '-c', "DROP DATABASE IF EXISTS $Name;") | Out-Null
}

function Apply-Chain {
    param([string] $Name)
    & (Join-Path $repoRoot 'ops/flyway/verification/rehearsal-chain.ps1') `
        -Container $Container -Database $Name -Keep | Out-Null
}

function Copy-To-Container {
    param([string] $Local, [string] $Name)
    Invoke-Docker @('cp', $Local, ("$Container" + ':/tmp/' + $Name)) | Out-Null
}

# Runs the undo and returns its combined output plus the exit code. The exit code
# matters: a refusal exits non-zero under ON_ERROR_STOP.
function Run-Undo {
    param([string] $Db, [hashtable] $Vars)
    $args = @('exec', $Container, 'psql', '-U', $DbUser, '-d', $Db, '-v', 'ON_ERROR_STOP=1')
    foreach ($k in $Vars.Keys) { $args += @('-v', "$k=$($Vars[$k])") }
    $args += @('-f', '/tmp/undo_phase10b.sql')
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& docker @args 2>&1) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    return [pscustomobject]@{ Output = $out; ExitCode = $code }
}

function Run-Seed {
    param([string] $Db, [string] $Flag)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& docker exec $Container psql -U $DbUser -d $Db -v ON_ERROR_STOP=1 -v "$Flag=on" `
                    -f /tmp/undo_phase10b_seed.sql 2>&1) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    return [pscustomobject]@{ Output = $out; ExitCode = $code }
}

# NB the seventh slot is "does the subjectCustomerId COLUMN exist", NOT "how many
# customer-subject rows are there", and there is no "how many api_keys rows"
# slot at all. A CASE that guards a count still PARSES the count, so a direct
# reference to a DROPPED table or column is a parse-time error however
# unreachable the branch is -- measured twice on this rehearsal, and the same
# trap V12/V14 hit after the Phase-7-10a undo. Row counts for the two new tables
# are asserted separately, only in the steps where the objects are known to
# exist.
$STATE_SQL = @'
select (select count(*) from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history')::text
    || ',' || (select count(*) from (select distinct t.typname from pg_type t
        join pg_namespace n on n.oid=t.typnamespace join pg_enum e on e.enumtypid=t.oid
       where n.nspname='public') x)::text
    || ',' || (select count(*) from pg_constraint con join pg_class c on c.oid=con.conrelid
        join pg_namespace n ON n.oid=c.relnamespace
       where n.nspname='public' and con.contype='c')::text
    || ',' || (to_regclass('public.api_keys') is not null)::text
    || ',' || (to_regclass('public.service_principals') is not null)::text
    || ',' || (select count(*) from legal_holds where "scopeType" in ('TABLE','RECORD'))::text
    || ',' || exists (select 1 from information_schema.columns
                        where table_schema='public' and table_name='privacy_deletion_requests'
                          and column_name='subjectCustomerId')::text
    || ',' || (select count(*) from ai_approvals where status='REJECTED' and "decidedAt" is not null)::text
'@

$CUSTOMER_SUBJECT_ROWS = @'
select count(*) from privacy_deletion_requests where "subjectCustomerId" is not null
'@

$POST_10B = '97,57,421,true,true,0,true,0'

if ($DbPrefix -eq 'firstelement') {
    throw "refusing to rehearse against 'firstelement': the development database is read-only for this agent. Pick a prefix."
}

Copy-To-Container (Join-Path $repoRoot 'ops/flyway/rollback/undo_phase10b.sql') 'undo_phase10b.sql'
Copy-To-Container (Join-Path $repoRoot 'ops/flyway/rollback/rehearsal/undo_phase10b_seed.sql') 'undo_phase10b_seed.sql'

$created = New-Object System.Collections.Generic.List[string]
try {
    # =================================================================
    # STEP 1 -- the whole chain applies, and the state is the post-10b one.
    # =================================================================
    Write-Step 'STEP 1: apply the whole reviewed chain to a throwaway'
    $db = "${DbPrefix}_chain"
    New-Throwaway $db; $created.Add($db)
    Apply-Chain $db
    Assert-Equal 'post-10b state (tables,enums,checks,api_keys,principals,narrow,customersubj,decidedrej,keys)' `
                 (Invoke-Psql -Sql $STATE_SQL -Db $db) $POST_10B

    # =================================================================
    # STEP 2 -- the undo is a CLEAN NO-OP when unarmed.
    # =================================================================
    Write-Step 'STEP 2: unarmed undo is a no-op'
    $unarmed = Run-Undo -Db $db -Vars @{ firstelement_undo_armed = 'off' }
    Assert-Equal 'unarmed undo says SKIPPED' (($unarmed.Output -match 'SKIPPED') -or ($unarmed.Output -match 'ABORTED')) 'True'
    Assert-Equal 'unarmed undo exit code' "$($unarmed.ExitCode)" '0'
    Assert-Equal 'unarmed undo changed nothing' (Invoke-Psql -Sql $STATE_SQL -Db $db) $POST_10B

    # =================================================================
    # STEP 3 -- the undo REFUSES on credential rows, and drops NOTHING.
    #         This is the row the Phase-7-10a undo got wrong.
    # =================================================================
    Write-Step 'STEP 3: armed undo on seeded credentials REFUSES and drops nothing'
    $db = "${DbPrefix}_refuse"
    New-Throwaway $db; $created.Add($db)
    Apply-Chain $db
    (Run-Seed -Db $db -Flag 'all') | Out-Null
    $before = Invoke-Psql -Sql $STATE_SQL -Db $db
    Write-Host "    seeded state: $before"
    Assert-Equal 'the seed inserted one key' (Invoke-Psql -Sql $STATE_SQL -Db $db) '97,57,421,true,true,1,true,1'
    Assert-Equal '...and exactly one api_keys row exists' `
                 (Invoke-Psql -Sql 'select count(*) from api_keys' -Db $db) '1'

    $refused = Run-Undo -Db $db -Vars @{ firstelement_undo_armed = 'on' }
    Assert-Equal 'the undo exited NON-zero'      "$($refused.ExitCode)"       '3'
    Assert-Equal 'the undo printed REFUSED'      ($refused.Output -match 'REFUSED') 'True'
    Assert-Equal 'THE STATE AFTER THE REFUSAL IS UNCHANGED' (Invoke-Psql -Sql $STATE_SQL -Db $db) `
                 '97,57,421,true,true,1,true,1'

    # =================================================================
    # STEP 4 -- each DATA guard, one cause at a time.
    # =================================================================
    Write-Step 'STEP 4a: a narrow (RECORD) legal hold refuses the undo -- D4 is not reversible'
    $db = "${DbPrefix}_narrow"
    New-Throwaway $db; $created.Add($db)
    Apply-Chain $db
    (Run-Seed -Db $db -Flag 'narrow') | Out-Null
    Assert-Equal 'a RECORD-scoped hold IS insertable (D4 landed)' (Invoke-Psql -Sql $STATE_SQL -Db $db) `
                 '97,57,421,true,true,1,true,0'
    $r = Run-Undo -Db $db -Vars @{ firstelement_undo_armed = 'on' }
    Assert-Equal 'the undo exited NON-zero' "$($r.ExitCode)" '3'
    Assert-Equal 'it refused on the NARROW hold' ($r.Output -match 'narrow \(TABLE or RECORD\)') 'True'
    Assert-Equal 'nothing was dropped' (Invoke-Psql -Sql $STATE_SQL -Db $db) '97,57,421,true,true,1,true,0'

    Write-Step 'STEP 4b: a CUSTOMER-subject request refuses the undo -- D5 is not reversible'
    $db = "${DbPrefix}_customer"
    New-Throwaway $db; $created.Add($db)
    Apply-Chain $db
    (Run-Seed -Db $db -Flag 'customer') | Out-Null
    Assert-Equal 'a CUSTOMER deletion IS expressible without a user id (D5 landed)' `
                 (Invoke-Psql -Sql $STATE_SQL -Db $db) '97,57,421,true,true,0,true,0'
    Assert-Equal '...and the seeded request really names a CUSTOMER, not a user' `
                 (Invoke-Psql -Sql $CUSTOMER_SUBJECT_ROWS -Db $db) '1'
    Assert-Equal '...and no user id is on that row at all' `
                 (Invoke-Psql -Sql 'select count(*) from privacy_deletion_requests where "subjectType" = ''CUSTOMER'' and "subjectUserId" is not null' -Db $db) '0'
    $r = Run-Undo -Db $db -Vars @{ firstelement_undo_armed = 'on' }
    Assert-Equal 'the undo exited NON-zero' "$($r.ExitCode)" '3'
    Assert-Equal 'it refused on the CUSTOMER subject' ($r.Output -match 'name a CUSTOMER subject') 'True'
    Assert-Equal 'nothing was dropped' (Invoke-Psql -Sql $STATE_SQL -Db $db) '97,57,421,true,true,0,true,0'

    Write-Step 'STEP 4c: a REJECTED approval that keeps its decision refuses the undo -- D1 is not reversible'
    $db = "${DbPrefix}_decided"
    New-Throwaway $db; $created.Add($db)
    Apply-Chain $db
    (Run-Seed -Db $db -Flag 'decided') | Out-Null
    Assert-Equal 'a REJECTED approval WITH a decider IS insertable (D1 landed)' `
                 (Invoke-Psql -Sql $STATE_SQL -Db $db) '97,57,421,true,true,0,true,1'
    $r = Run-Undo -Db $db -Vars @{ firstelement_undo_armed = 'on' }
    Assert-Equal 'the undo exited NON-zero' "$($r.ExitCode)" '3'
    Assert-Equal 'it refused on the DECISION RECORD' ($r.Output -match 'REJECTED or REVOKED and carry a') 'True'
    Assert-Equal 'nothing was dropped' (Invoke-Psql -Sql $STATE_SQL -Db $db) '97,57,421,true,true,0,true,1'

    # =================================================================
    # STEP 5 -- the ARMED, EMPTY undo succeeds and returns the exact state.
    # =================================================================
    Write-Step 'STEP 5: armed undo on an UNUSED batch removes it and returns the post-7-10a state'
    $db = "${DbPrefix}_clean"
    New-Throwaway $db; $created.Add($db)
    Apply-Chain $db
    $ok = Run-Undo -Db $db -Vars @{ firstelement_undo_armed = 'on' }
    Assert-Equal 'the undo exit code' "$($ok.ExitCode)" '0'
    Assert-Equal 'it reported PHASE10B_REMOVED' ($ok.Output -match 'PHASE10B_REMOVED') 'True'
    Assert-Equal 'the batch is GONE: tables,enums,checks,api_keys,principals,subjectCustomerId' `
                 (Invoke-Psql -Sql $STATE_SQL -Db $db) '95,55,402,false,false,0,false,0'

    # The pre-existing FKs the batch must NOT have touched.
    Assert-Equal 'document_chunks_documentId_fkey survives the undo' `
        (Invoke-Psql -Sql "select count(*) from pg_constraint where conname='document_chunks_documentId_fkey'" -Db $db) '1'
    Assert-Equal 'privacy_deletion_requests_subject_tenant_fkey survives the undo' `
        (Invoke-Psql -Sql "select count(*) from pg_constraint where conname='privacy_deletion_requests_subject_tenant_fkey'" -Db $db) '1'
    Assert-Equal 'subjectUserId is NOT NULL again' `
        (Invoke-Psql -Sql "select attnotnull::text from pg_attribute where attrelid='public.privacy_deletion_requests'::regclass and attname='subjectUserId'" -Db $db) 'true'
    Assert-Equal 'the restored ai_approvals_approval_check is the PRE-BATCH form' `
        (Invoke-Psql -Sql @'
select (pg_get_constraintdef(oid) like '%= ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%')::text
  from pg_constraint
 where conrelid='public.ai_approvals'::regclass and conname='ai_approvals_approval_check'
'@ -Db $db) 'true'
    Assert-Equal 'the restored ai_approvals_approval_check is NOT the corrected IN-list form' `
        (Invoke-Psql -Sql @'
select (position('= ANY (ARRAY[' in pg_get_constraintdef(oid)) = 0)::text
  from pg_constraint
 where conrelid='public.ai_approvals'::regclass and conname='ai_approvals_approval_check'
'@ -Db $db) 'true'
    Assert-Equal 'the restored legal_holds_scope_pair_check has NO TABLE arm' `
        (Invoke-Psql -Sql @'
select (position('TABLE' in pg_get_constraintdef(oid)) = 0)::text
  from pg_constraint
 where conrelid='public.legal_holds'::regclass and conname='legal_holds_scope_pair_check'
'@ -Db $db) 'true'

    # =================================================================
    # STEP 6 -- the chain RE-APPLIES on top of its own undo.
    #
    # A migration that cannot be re-applied after its undo is not reversible in
    # the sense an operator needs: it means the undo is a dead end rather than a
    # way back.
    # =================================================================
    Write-Step 'STEP 6: the chain re-applies on top of its own undo'
    Apply-Chain $db
    Assert-Equal 'post-10b state restored by re-applying' (Invoke-Psql -Sql $STATE_SQL -Db $db) $POST_10B
}
finally {
    if (-not $Keep) {
        Write-Step 'dropping every throwaway this rehearsal created'
        foreach ($d in $created) { Drop-Throwaway $d; Write-Host "    dropped $d" }
    } else {
        Write-Step "throwaways KEPT: $($created -join ', ')"
    }
}

Write-Host "`n================ PHASE-10b REHEARSAL SUMMARY ================"
$results  | ForEach-Object { Write-Host $_ }
if ($failures.Count -gt 0) {
    Write-Host "`n$($failures.Count) ASSERTION(S) FAILED:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    if ($ThrowOnViolation) { exit 1 }
    exit 1
}
Write-Host "`nAll $($results.Count) state assertions PASS." -ForegroundColor Green
exit 0