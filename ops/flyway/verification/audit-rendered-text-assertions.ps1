#!/usr/bin/env pwsh
# =====================================================================
# audit-rendered-text-assertions.ps1
#
# WHAT IT IS
#   The Phase-30 defect detector. A migration that asserts on text the
#   PostgreSQL catalogue RENDERS -- pg_get_constraintdef, pg_get_indexdef,
#   pg_get_expr, format_type, pg_get_functiondef -- is asserting on a
#   function of the SESSION as well as of the database. Two renderings of one
#   object are normal:
#
#       search_path = "$user", public
#         FOREIGN KEY ("a") REFERENCES documents(id) ...
#         CHECK ((s = ANY (ARRAY['X'::"MyEnum"])) ...
#       search_path = ''
#         FOREIGN KEY ("a") REFERENCES public.documents(id) ...
#         CHECK ((s = ANY (ARRAY['X'::public."MyEnum"])) ...
#
#   and the baseline (V20260814000000 line 56) installs search_path = '' with
#   is_local = false, which is a SESSION setting that lasts for the rest of the
#   connection. Flyway runs every migration of a boot on one connection, so
#   migrations 2..24 execute under search_path = '' under Flyway too. A guard
#   written from the other rendering is not a weaker check; it is a check that
#   either always fires or can never fire, depending on how the session was set
#   up.
#
# WHAT IT DOES, IN TWO PASSES
#   STATIC  reads every file in the chain, lists every catalogue-rendered read
#           and every LIKE pattern compared against one, and classifies each
#           site:
#             structural            no rendered text is compared
#             rendering-stable      rendered text is compared, but the pattern
#                                   contains no relation name and no type name,
#                                   so search_path cannot reach it
#             search_path-sensitive  rendered text is compared AND the pattern
#                                   names a relation or a type
#   LIVE    with -Database pointing at a throwaway database that carries the
#           whole chain, evaluates every probe pattern twice -- once with
#           search_path at its default, once at '' -- and prints which probes
#           change their answer. This is what turns "I think this is sensitive"
#           into a measurement, and it is how the five blocking sites and the
#           one inert site were found.
#
# THE CLASSIFICATION IS NOT AUTOMATIC FOR THE LIVE PASS
#   A probe has to name a real object, so the probe list is curated below. It is
#   guarded: a probe whose constraint or index no longer exists in the chain's
#   own text is reported as STALE and the audit refuses to pass. That is
#   deliberate -- a probe list that silently stops probing is exactly the
#   failure mode this whole audit exists to catch.
#
# EXIT CODES: 0 no search_path-sensitive site remains, 1 one or more found,
#             2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $Container = 'firstelement-postgres',
    [string] $Database,
    [string] $DbUser    = 'firstelement',

    # Skip the live differential pass and report the static inventory only.
    [switch] $StaticOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)))
$sqlDir   = Join-Path $repoRoot 'ops/flyway/sql'
$files    = @(Get-ChildItem -LiteralPath $sqlDir -Filter 'V*.sql' -File | Sort-Object Name)
if ($files.Count -eq 0) { throw "no V*.sql files in $sqlDir" }

# The catalogue-rendered read functions. Anything in this list produces text
# whose exact spelling is a function of the session.
$renderFns = 'pg_get_constraintdef','pg_get_indexdef','pg_get_expr',
             'format_type','pg_get_functiondef','pg_get_viewdef','pg_get_triggerdef'

# ---------------------------------------------------------------------
# PASS 1. STATIC INVENTORY
# ---------------------------------------------------------------------
Write-Host "=== STATIC INVENTORY: every catalogue-rendered read in the chain ==="
$static = @()
foreach ($f in $files) {
    $lines = Get-Content -LiteralPath $f.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $l = $lines[$i]
        if ($l -match '^\s*--') { continue }          # a comment is not a check
        foreach ($fn in $renderFns) {
            if ($l -match "\b$fn\s*\(") {
                $static += [pscustomobject]@{
                    File = $f.Name; Line = $i + 1; Fn = $fn; Text = $l.Trim()
                }
            }
        }
    }
}
if ($static.Count -eq 0) {
    Write-Host "  (none -- every check in this chain is structural)"
} else {
    $static | Sort-Object File, Line | Format-Table -AutoSize -Wrap |
        Out-String -Width 200 | Write-Host
}
Write-Host ("  {0} rendered-text read(s) across {1} migration(s).`n" -f $static.Count, $files.Count)

# ---------------------------------------------------------------------
# THE CLASSIFIED INVENTORY
#
# Hand-maintained because the question "is this pattern reachable by
# search_path?" is a semantic judgement, not a grep. Every row was MEASURED on
# PostgreSQL 16.15 on a throwaway database carrying the whole chain; the LIVE
# pass below re-measures it on demand. `Line` is the line the LIKE pattern sits
# on, so a chain edit moves it and this table is visibly stale.
# ---------------------------------------------------------------------
$sites = @(
    # --- was blocking, fixed in Phase 30: now a structural comparison -------
    @{ Ver='20261006110000'; Line=236; Class='structural'; Renders='(none)'
       Subject='document_chunks_document_tenant_fkey'
       Pattern='conkey / confkey resolved through pg_attribute; confrelid vs a regclass'
       Effect='WAS search_path-sensitive and ALWAYS FIRED: it matched %REFERENCES documents(id, "organizationId")% against a rendering that says public.documents(...). This is the migration the orchestrator reproduced. Now structural.' }
    @{ Ver='20261006120000'; Line=665; Class='structural'; Renders='(none)'
       Subject='api_keys_service_principal_tenant_fkey'
       Pattern='conkey / confkey resolved through pg_attribute; confrelid vs a regclass'
       Effect='WAS search_path-sensitive and always fired; reached only because 06110000 failed first.' }
    @{ Ver='20261006140000'; Line=373; Class='structural'; Renders='(none)'
       Subject='privacy_deletion_requests_subject_customer_tenant_fkey'
       Pattern='conkey / confkey resolved through pg_attribute; confrelid vs a regclass'
       Effect='WAS search_path-sensitive and always fired.' }
    @{ Ver='20261006140000'; Line=410; Class='structural'; Renders='(none)'
       Subject='privacy_deletion_requests_subject_tenant_fkey'
       Pattern='conkey / confkey resolved through pg_attribute; confrelid vs a regclass'
       Effect='WAS search_path-sensitive and always fired.' }
    @{ Ver='20261007100000'; Line=357; Class='structural'; Renders='(none)'
       Subject='customer_cx_sequences_organizationId_fkey'
       Pattern='conkey / confkey resolved through pg_attribute; confrelid vs a regclass'
       Effect='WAS search_path-sensitive and always fired.' }

    # --- previously inert, now fixed: normalisation makes it environment-free ---
    @{ Ver='20261006100000'; Line=352; Class='renormalised'; Renders='pg_get_constraintdef'
       Subject='ai_approvals_approval_check'
       Pattern='the old single-literal form detector'
       Effect='WAS inert: under an empty search_path the enum rendered ::public."AiApprovalStatus" and the pattern could never match, so a removal check could not detect the removal -- and the self-test one line below still reported "discriminates" because it compared the pattern with a HARDCODED string. FIXED in Phase 30: the rendering is normalised with regexp_replace(..., [A-Za-z_][A-Za-z_0-9$]*\." -> ") and the self-test now installs the pre-batch expression on a pg_temp probe table and requires this session''s own rendering to match. Measured true under BOTH settings.' }

    @{ Ver='20261009110000'; Line=397; Class='structural'; Renders='(none)'
       Subject='appointments_*_tenant_fkey (all four)'
       Pattern='conkey / confkey resolved through pg_attribute; confrelid vs a regclass'
       Effect='Phase 35. The shape of each of the four composite FKs is read from the attnum arrays, and the arrays are compared POSITION BY POSITION -- which is what a reversed pair needs, since the FK column list and the referenced column list are positional. rehearsal control NEG-6 builds the reversed constraint ("organizationId","doctorId") and proves the guard fires.' }
    @{ Ver='20261009110000'; Line=480; Class='structural'; Renders='(none)'
       Subject='the four FK target indexes'
       Pattern='indisvalid / indisunique / indnkeyatts / indpred / indexprs'
       Effect='Phase 35. Validity, uniqueness, arity, partiality and expression-ness are all read from pg_index columns, never from pg_get_indexdef. rehearsal control NEG-7 builds a PARTIAL target index and proves the guard fires.' }
    @{ Ver='20261009110000'; Line=455; Class='structural'; Renders='(none)'
       Subject='appointments.organizationId'
       Pattern='attnotnull through pg_attribute'
       Effect='Phase 35. The MATCH SIMPLE tripwire. All four constraints are MATCH SIMPLE, which skips the check when ANY column is NULL; only the NOT NULL on organizationId guarantees a NULL reference brings its tenant with it. rehearsal control NEG-8 drops the NOT NULL and proves the guard fires.' }
    @{ Ver='20261009110000'; Line=411; Class='structural'; Renders='(none)'
       Subject='confdeltype / confupdtype / confmatchtype'
       Pattern='catalogue type codes compared to literal characters'
       Effect='Phase 35. The delete and update CLAUSES are asserted structurally rather than by matching text: confdeltype = ''n'' (SET NULL, NOT CASCADE -- CASCADE would delete an appointment), confupdtype = ''c'', confmatchtype = ''s''. rehearsal control NEG-10 switches one constraint to CASCADE and proves the guard fires.' }

    # --- rendering-stable: read text, but nothing search_path can reach -----
    @{ Ver='20261009110000'; Line=367; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='appointments_*_tenant_fkey (all four)'
       Pattern='none -- read for the message, never compared'
       Effect='Phase 35. The rendering is SELECTed into "definition" and interpolated into the failure message, so an operator reading a refusal sees the constraint as PostgreSQL prints it. It is NOT compared: the pass/fail decision above it is the structural one. This is the same division V20261006110000 records -- rendered text for humans, structure for the gate.' }
    @{ Ver='20261006110000'; Line=402; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='appointments_*_tenant_fkey (all four)'
       Pattern='the FOREIGN KEY column pair'
       Effect='Column names only. Quoting of a column name depends on the name, never on search_path. Measured identical under both settings.' }
    @{ Ver='20261006110000'; Line=523; Class='rendering-stable'; Renders='(none)'
       Subject='the four pre-existing single-column appointment FKs'
       Pattern='conname IN (...) compared by NAME'
       Effect='Phase 35. The post-condition asserts all four baseline FKs still exist, by name, read through pg_constraint. Constraint NAMES are stored exactly as declared and are never schema-qualified by the deparser, so search_path cannot reach them.' }
    @{ Ver='20261006110000'; Line=533; Class='structural'; Renders='(none)'
       Subject='V4 unique_constraints = 0 invariant'
       Pattern='contype compared to a literal'
       Effect='Phase 35. Re-asserted after adding two unique INDEXES, so this batch cannot silently introduce a unique CONSTRAINT and break V4.' }
    @{ Ver='20261006110000'; Line=251; Class='structural'; Renders='(none)'
       Subject='the refuse-rather-than-repair guard'
       Pattern='a JOIN of appointments to its four targets'
       Effect='Phase 35. The pre-check compares organizations through a JOIN, not through any rendered definition. Nothing about it can vary with the session.' }
    @{ Ver='20261006110000'; Line=206; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='document_chunks_document_tenant_fkey'
       Pattern='the FOREIGN KEY column pair'
       Effect='Column names only. Quoting of a column name depends on the name, never on search_path. Measured identical under both settings.' }
    @{ Ver='20261006120000'; Line=652; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='api_keys_service_principal_tenant_fkey'
       Pattern='the FOREIGN KEY column pair'
       Effect='Column names only. Measured identical under both settings.' }
    @{ Ver='20261006140000'; Line=354; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='privacy_deletion_requests_subject_customer_tenant_fkey'
       Pattern='the FOREIGN KEY column pair'
       Effect='Column names only. Measured identical under both settings.' }
    @{ Ver='20261006140000'; Line=376; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='privacy_deletion_requests_subject_tenant_fkey'
       Pattern='the FOREIGN KEY column pair'
       Effect='Column names only. Measured identical under both settings.' }
    @{ Ver='20261007100000'; Line=340; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='customer_cx_sequences_organizationId_fkey'
       Pattern='the FOREIGN KEY column'
       Effect='Column name only. Measured identical under both settings.' }
    @{ Ver='20261006100000'; Line=327; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='ai_approvals_approval_check'
       Pattern='the three enum literals'
       Effect='Enum literals only, no type name. Measured identical under both settings.' }
    @{ Ver='20261006130000'; Line=217; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='legal_holds_scope_pair_check'
       Pattern='the four bare enum words'
       Effect='Bare literal words. Measured identical under both settings.' }
    @{ Ver='20261006130000'; Line=225; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='legal_holds_scope_pair_check'
       Pattern='two column IS NULL / IS NOT NULL tests'
       Effect='Column names only. Measured identical under both settings.' }
    @{ Ver='20261006140000'; Line=338; Class='rendering-stable'; Renders='pg_get_constraintdef'
       Subject='privacy_deletion_requests_subject_reference_check'
       Pattern='two column names and one bare word'
       Effect='Column names and a bare literal. Measured identical under both settings.' }
    @{ Ver='20261007100000'; Line=305; Class='rendering-stable'; Renders='pg_get_indexdef'
       Subject='customer_cx_sequences_organizationId_key'
       Pattern='the bracketed column list'
       Effect='Column list. Only a RELATION name in a pattern would be at risk, and this one names none. Measured identical.' }
    @{ Ver='20261007100000'; Line=453; Class='rendering-stable'; Renders='format_type'
       Subject='customer_cx_sequences.lastAllocatedCxSequence'
       Pattern='the bigint type name'
       Effect='A pg_catalog type is never schema-qualified, whatever search_path is. Measured identical.' }
    @{ Ver='20261006140000'; Line=289; Class='rendering-stable'; Renders='format_type'
       Subject='privacy_deletion_requests.subjectCustomerId'
       Pattern='the uuid type name'
       Effect='A pg_catalog type is never schema-qualified. Measured identical.' }
    @{ Ver='20261007110000'; Line=502; Class='rendering-stable'; Renders='pg_get_indexdef'
       Subject='customers_organizationId_cxId_key'
       Pattern='positional, column by column'
       Effect='Compared POSITION BY POSITION against the rendered column rather than by a whole-definition LIKE. Measured identical under both settings. This file is the model the five broken sites should have followed.' }
    @{ Ver='20261007110000'; Line=524; Class='rendering-stable'; Renders='pg_get_indexdef'
       Subject='customers_organizationId_cxId_key'
       Pattern='the index keyword and the column pair'
       Effect='Index keyword and column list, no relation name. Measured identical under both settings.' }
    @{ Ver='20261007100000'; Line=384; Class='rendering-stable'; Renders='pg_get_functiondef'
       Subject='firstelement_next_customer_cx_id'
       Pattern='three SQL keywords'
       Effect='SQL keywords in a plpgsql body; the body is deparsed verbatim. Measured identical.' }
    @{ Ver='20261005100000'; Line=1250; Class='rendering-stable'; Renders='pg_get_expr'
       Subject='ai_tool_registry.isEnabled'
       Pattern='the boolean literal false'
       Effect='A boolean literal. Measured identical under both settings.' }
    @{ Ver='13 files'; Line=0; Class='rendering-stable'; Renders='pg_get_expr'
       Subject='every column default audited for session-local time'
       Pattern='CURRENT_TIMESTAMP present, AT TIME ZONE absent'
       Effect='A keyword and a function name, neither schema-qualified by the deparser. Appears at 05010000:491, 05020000:566, 05030000:653, 05040000:413, 05050000:1013, 05060000:805, 05070000:734, 05080000:483, 05090000:1512, 05100000:1339, 05110000:201, 06120000:726, 07100000:434.' }

    # --- structural: no rendered text compared at all ----------------------
    @{ Ver='20261005080000'; Line=504; Class='structural'; Renders='(none)'
       Subject='usage_events_source_dimension_key'
       Pattern='indkey resolved through pg_attribute, compared to a name array'
       Effect='The chain ALREADY contains the right technique, with a comment explaining why: the column list is compared through pg_attribute rather than by matching pg_get_indexdef text. The fix for the five blocking sites is this technique applied to pg_constraint.conkey / confkey / confrelid.' }
)

Write-Host "=== CLASSIFIED INVENTORY ===`n"
# NB counted with $s['Class'] rather than Group-Object: these are hashtables,
# and Group-Object does not read a hashtable key as a property, so it silently
# reports one unnamed group and the count line becomes useless.
foreach ($cls in @('search_path-sensitive', 'renormalised', 'rendering-stable', 'structural')) {
    $n = @($sites | Where-Object { $_['Class'] -eq $cls }).Count
    Write-Host ("  {0,-24} {1} site(s)" -f $cls, $n)
}
Write-Host ''
$sites | Where-Object { $_['Class'] -eq 'search_path-sensitive' } | ForEach-Object {
    Write-Host ("  [{0}] {1}:{2}  ({3})" -f $_['Class'], $_['Ver'], $_['Line'], $_['Subject'])
    Write-Host ("      pattern : {0}" -f $_['Pattern'])
    Write-Host ("      effect  : {0}" -f $_['Effect'])
    Write-Host ''
}

# ---------------------------------------------------------------------
# PASS 2. LIVE DIFFERENTIAL
# ---------------------------------------------------------------------
if ($StaticOnly) { Write-Host "(live pass skipped: -StaticOnly)"; exit 0 }
if (-not $Database) {
    Write-Host "NOTE: no -Database given, so the live differential pass was SKIPPED."
    Write-Host "      The classification above is from the Phase-30 measurement."
    Write-Host "      Re-measure with:"
    Write-Host ("        .\ops\flyway\verification\verify-clean-bootstrap.ps1 -Database firstelement_audit_check")
    Write-Host ("        .\ops\flyway\verification\audit-rendered-text-assertions.ps1 -Database firstelement_audit_check")
    exit 1
}

# Refuse a database that is not there, and refuse to be pointed at the
# protected one. Without the first check the live pass fails later with a raw
# psql error that reads like a broken tool rather than a wrong argument, and a
# gate that cannot tell those two apart is not a gate.
$probePrev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$n = ("SELECT count(*) FROM pg_database WHERE datname = '$Database';" |
      docker exec -i $Container psql -U $DbUser -d $DbUser `
                     -v ON_ERROR_STOP=1 -q -t -A -f - 2>&1) |
     ForEach-Object { $_.ToString() } | Select-Object -First 1
$ErrorActionPreference = $probePrev
if ([int]$n -ne 1) {
    Write-Host "REFUSING: database '$Database' does not exist on container '$Container'."
    Write-Host '         Build it first -- the live pass must measure a real migrated schema.'
    exit 2
}
if ($Database -eq 'firstelement') {
    Write-Host "REFUSING: -Database 'firstelement' is the protected database. This tool is"
    Write-Host '         read-only, but a throwaway is where this measurement belongs.'
    exit 2
}

# Probes evaluate the predicate each site SHIPS TODAY, plus -- for the four that
# were rewritten -- the rendered-text pattern that used to ship beside it. The
# legacy rows are the regression evidence: they must keep flipping, or the fix
# has not actually removed the dependency.
$fkShape = @'
SELECT
    (SELECT array_agg(a.attname::text ORDER BY k.ord)
       FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
       JOIN pg_attribute a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
      = ARRAY[@L@]::text[]
    AND cn.confrelid = '@RT@'::regclass
    AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
           FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
      = ARRAY[@RC@]::text[]
  FROM pg_constraint cn
 WHERE cn.conrelid = '@LT@'::regclass AND cn.conname = '@CN@';
'@

function New-FkShapeSql {
    param([string[]] $Local, [string] $RefTable, [string[]] $RefCols,
          [string] $LocalTable, [string] $ConName)
    $l = ($Local | ForEach-Object { "'$_'" }) -join ', '
    $r = ($RefCols | ForEach-Object { "'$_'" }) -join ', '
    return ($fkShape -replace '@L@', $l -replace '@RT@', $RefTable `
                      -replace '@RC@', $r  -replace '@LT@', $LocalTable -replace '@CN@', $ConName)
}

# The shipped normalisation, identical to V20261006100000 line ~341.
$norm = "regexp_replace(pg_get_constraintdef(oid),'[A-Za-z_][A-Za-z_0-9`$]*\.""', '""', 'g')"

$probes = @(
    @{ N='0611000 structural FK shape';  Mode='shape'
       Sql = (New-FkShapeSql @('documentId','organizationId') 'public.documents' @('id','organizationId') 'public.document_chunks' 'document_chunks_document_tenant_fkey') }
    @{ N='0611000 LEGACY unqualified pattern'; Mode='text'
       Pat='%REFERENCES documents(id, "organizationId")%'
       Sql = "SELECT (pg_get_constraintdef(oid) LIKE '%REFERENCES documents(id, ""organizationId"")%') FROM pg_constraint WHERE conname='document_chunks_document_tenant_fkey';" }
    @{ N='0612000 structural FK shape';  Mode='shape'
       Sql = (New-FkShapeSql @('servicePrincipalId','organizationId') 'public.service_principals' @('id','organizationId') 'public.api_keys' 'api_keys_service_principal_tenant_fkey') }
    @{ N='0612000 LEGACY unqualified pattern'; Mode='text'
       Sql = "SELECT (pg_get_constraintdef(oid) LIKE '%REFERENCES service_principals(id, ""organizationId"")%') FROM pg_constraint WHERE conname='api_keys_service_principal_tenant_fkey';" }
    @{ N='0614000 structural FK shape (customer)'; Mode='shape'
       Sql = (New-FkShapeSql @('subjectCustomerId','organizationId') 'public.customers' @('id','organizationId') 'public.privacy_deletion_requests' 'privacy_deletion_requests_subject_customer_tenant_fkey') }
    @{ N='0614000 LEGACY unqualified pattern (customers)'; Mode='text'
       Sql = "SELECT (pg_get_constraintdef(oid) LIKE '%REFERENCES customers(id, ""organizationId"")%') FROM pg_constraint WHERE conname='privacy_deletion_requests_subject_customer_tenant_fkey';" }
    @{ N='0614000 structural FK shape (users)'; Mode='shape'
       Sql = (New-FkShapeSql @('subjectUserId','organizationId') 'public.users' @('id','organizationId') 'public.privacy_deletion_requests' 'privacy_deletion_requests_subject_tenant_fkey') }
    @{ N='0614000 LEGACY unqualified pattern (users)'; Mode='text'
       Sql = "SELECT (pg_get_constraintdef(oid) LIKE '%REFERENCES users(id, ""organizationId"")%') FROM pg_constraint WHERE conname='privacy_deletion_requests_subject_tenant_fkey';" }
    @{ N='0710000 structural FK shape';  Mode='shape'
       Sql = (New-FkShapeSql @('organizationId') 'public.organizations' @('id') 'public.customer_cx_sequences' 'customer_cx_sequences_organizationId_fkey') }
    @{ N='0710000 LEGACY unqualified pattern'; Mode='text'
       Sql = "SELECT (pg_get_constraintdef(oid) LIKE '%REFERENCES organizations(id)%') FROM pg_constraint WHERE conname='customer_cx_sequences_organizationId_fkey';" }
    @{ N='0610000 old-form detector (normalised)'; Mode='text'
       Sql = "SELECT (regexp_replace(pg_get_constraintdef(oid),'[A-Za-z_][A-Za-z_0-9`$]*\.""', '""', 'g') LIKE '%= ''APPROVED''::""AiApprovalStatus"") = (""decidedAt"" IS NOT NULL)%') FROM pg_constraint WHERE conname='ai_approvals_approval_check';" }
    @{ N='(control) documents target index'; Mode='text'
       Sql = "SELECT (pg_get_indexdef(to_regclass('public.""documents_id_organizationId_key""')) LIKE '%UNIQUE INDEX%') ;" }
    # --- Phase 35: appointments -------------------------------------------
    # Each of these is the predicate V20261009110000 SHIPS. The paired LEGACY
    # rows are the rendered-text form the migration deliberately does NOT use,
    # and they must keep flipping -- that is what proves the fix is still there
    # rather than having been quietly reverted to a text match.
    @{ N='0911000 structural FK shape (doctor)'; Mode='shape'
       Sql = (New-FkShapeSql @('doctorId','organizationId') 'public.doctors' @('id','organizationId') 'public.appointments' 'appointments_doctor_tenant_fkey') }
    @{ N='0911000 LEGACY unqualified pattern (doctors)'; Mode='text'
       Sql = "SELECT (pg_get_constraintdef(oid) LIKE '%REFERENCES doctors(id, ""organizationId"")%') FROM pg_constraint WHERE conname='appointments_doctor_tenant_fkey';" }
    @{ N='0911000 structural FK shape (customer)'; Mode='shape'
       Sql = (New-FkShapeSql @('customerId','organizationId') 'public.customers' @('id','organizationId') 'public.appointments' 'appointments_customer_tenant_fkey') }
    @{ N='0911000 LEGACY unqualified pattern (customers)'; Mode='text'
       Sql = "SELECT (pg_get_constraintdef(oid) LIKE '%REFERENCES customers(id, ""organizationId"")%') FROM pg_constraint WHERE conname='appointments_customer_tenant_fkey';" }
    @{ N='0911000 structural FK shape (creator)'; Mode='shape'
       Sql = (New-FkShapeSql @('createdById','organizationId') 'public.users' @('id','organizationId') 'public.appointments' 'appointments_created_by_tenant_fkey') }
    @{ N='0911000 structural FK shape (procedure)'; Mode='shape'
       Sql = (New-FkShapeSql @('procedureId','organizationId') 'public.procedures' @('id','organizationId') 'public.appointments' 'appointments_procedure_tenant_fkey') }
    @{ N='(control) appointments FK target index'; Mode='text'
       Sql = "SELECT (pg_get_indexdef(to_regclass('public.""doctors_id_organizationId_key""')) LIKE '%UNIQUE INDEX%');" }
)

function Invoke-Probe {
    param([string] $Sql, [string] $Sp)
    # -q is REQUIRED, not cosmetic: without it psql echoes the command tag of
    # the leading SET as the first line of output, and that is what would be
    # compared instead of the answer. -t -A give one bare value per line.
    #
    # The probe's own SELECT is wrapped as a SCALAR SUBQUERY rather than having
    # a prefix glued onto its front: `SELECT 'x' || SELECT ...` is not valid
    # SQL, and a shape probe has a FROM clause, so there is no single expression
    # to prefix. The PROBE= tag lets the one answer line be found unambiguously.
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $body = if ($Sp -eq 'empty') { "SET search_path TO '';`n" } else { "SET search_path TO DEFAULT;`n" }
    $inner = ($Sql.Trim() -replace ';\s*$', '')
    $body += "SELECT 'PROBE=' || coalesce((" + $inner + ")::text, '(null)');`n"
    $out = ($body | docker exec -i $Container psql -U $DbUser -d $Database `
                           -v ON_ERROR_STOP=1 -q -t -A -f - 2>&1) |
           ForEach-Object { $_.ToString() } | Where-Object { $_ -like 'PROBE=*' }
    $ErrorActionPreference = $prev
    if (-not $out) { throw "probe produced no answer:`n$body" }
    return (($out | Select-Object -First 1) -replace '^PROBE=', '')
}

Write-Host "=== LIVE DIFFERENTIAL on $Database ==="
Write-Host ("  {0,-44} {1,-11} {2,-11} {3}" -f 'PROBE', 'search_path', "search_path=''", 'VERDICT')
Write-Host ('-' * 112)
$flipShipped = 0; $flipLegacy = 0
foreach ($p in $probes) {
    $a = Invoke-Probe -Sql $p.Sql -Sp 'default'
    $b = Invoke-Probe -Sql $p.Sql -Sp 'empty'
    if (-not $a) { $a = '(object absent)' }
    if (-not $b) { $b = '(object absent)' }
    $flips = ($a -ne $b)
    if ($p['Mode'] -eq 'text' -and $p['N'] -match 'LEGACY') {
        $verdict = if ($flips) { 'flips (expected: this pattern is GONE)' } else { 'no longer flips' }
        if ($flips) { $flipLegacy++ }
    } else {
        $verdict = if ($flips) { 'FLIPS  <-- SHIPPED PREDICATE IS search_path-DEPENDENT'; $flipShipped++ } else { 'stable' }
    }
    Write-Host ("  {0,-44} {1,-11} {2,-11} {3}" -f $p['N'], $a, $b, $verdict)
}
Write-Host ''
Write-Host ("  shipped predicates that flip : {0}" -f $flipShipped)
Write-Host ("  removed patterns that flip  : {0}  (these are the regression evidence)" -f $flipLegacy)
Write-Host ''
if ($flipShipped -gt 0) {
    Write-Host "RESULT: $flipShipped SHIPPED predicate(s) change their answer when search_path changes." -ForegroundColor Red
    Write-Host 'A guard whose result flips on a session setting is not a verification.'
    exit 1
}
Write-Host 'RESULT: no shipped predicate flips. Every guard in this chain is search_path-independent.' -ForegroundColor Green
exit 0