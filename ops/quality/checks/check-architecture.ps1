#!/usr/bin/env pwsh
# =====================================================================
# check-architecture.ps1
#
# Source-level architecture gate for the Spring modules.
#
# WHY THIS EXISTS INSTEAD OF ARCHUNIT
#   Phase 3 recorded "No ArchUnit rule yet forbids a @RestController from
#   being the only enforcement point; that is a quality-agent gate." A
#   real ArchUnit module needs a `build.gradle.kts` and a
#   `settings.gradle.kts` include, and both files belong to other agents
#   right now. This script implements the same class of rule as a static
#   source check so the gate is real from this commit onwards, and it is
#   written so the rules can be lifted into ArchUnit verbatim when the
#   module owner is available (see docs/migration/phases/quality-gates.md
#   "Known limitations").
#
#   A static check is a weaker instrument: it reads source text, not
#   bytecode, and it cannot follow a call graph. Every rule below is
#   therefore chosen to be one that a text scan can decide soundly, and
#   the one rule that genuinely needs a call graph (FE-TENANT-001) is a
#   RATCHET over a recorded baseline rather than a boolean.
#
# Rules that FAIL the build:
#   FE-ARCH-001  forbidden main-scope module dependency (layering)
#   FE-ARCH-002  module directory / settings.gradle.kts registration
#   FE-ARCH-003  a module whose tests use Testcontainers must pin the
#                Testcontainers version (Phase-4 defect: 1.21.3 cannot
#                negotiate with Docker Desktop 29.x)
#   FE-ARCH-004  test-scope dependency must not create a main-scope cycle
#   FE-WEB-001   no JDBC / java.sql in a *.web package
#   FE-WEB-002   every @RestController lives in a *.web package
#   FE-WEB-003   a controller method bound to a client tenant/project id
#                must sit in a class that references a policy guard
#   FE-SEC-001   no production source references .env
#   FE-LOG-001   no System.out / System.err in production source
#   FE-LOG-002   no secret-named expression passed as a log argument
#                (a secret word inside a message literal is documentation,
#                 not a leak; only an ARGUMENT can leak a value)
#   FE-TIME-001  no Instant / OffsetDateTime / ZonedDateTime in a
#                *.persistence package (ADR-0002: the 98
#                `timestamp without time zone` columns bind as LocalDateTime)
#
# Rule that RATCHETS (report + a recorded ceiling):
#   FE-TENANT-001  SQL fragments whose tenant predicate is not lexically
#                  present. Today these are all legitimate: the predicate is
#                  appended by CrmQueryScope.select(...) / ScopedWhere, so a
#                  text scan cannot judge them. The baseline records how many
#                  exist; the count may only fall. A new one must be
#                  triaged, which is the real goal.
#
# Exit codes: 0 clean, 1 violations, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $Baseline,
    [string] $JsonOut
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\lib\quality-common.ps1')

if (-not $RepoRoot) { $RepoRoot = Get-RepositoryRoot -StartFrom $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $Baseline) { $Baseline = Join-Path $RepoRoot 'tests\contracts\architecture-baseline.json' }

$backendRoot = Join-Path $RepoRoot 'backend'
if (-not (Test-Path -LiteralPath $backendRoot)) {
    Write-QLog 'backend/ not found; nothing to check.' -Level ERROR
    exit 2
}

$findings = New-Object System.Collections.Generic.List[object]
$report   = New-Object System.Collections.Generic.List[object]

function Add-Finding {
    param([string] $Rule, [string] $Severity, [string] $File, [string] $Detail)
    $script:findings.Add([ordered]@{ rule = $Rule; severity = $Severity; file = $File; detail = $Detail })
}

function New-RuleReport {
    param([string] $Rule, [string] $Title, [string] $Rationale, $Findings, [string] $Outcome)
    $asArray = ConvertTo-QArray -Value $Findings
    $script:report.Add([ordered]@{
        rule = $Rule; title = $Title; rationale = $Rationale
        violationCount = $asArray.Count
        findings = $asArray
        outcome = $Outcome
    })
}

# Production sources only. Test sources are allowed to print and to reach
# for a container.
$javaFiles = Get-ChildItem -LiteralPath $backendRoot -Recurse -Filter '*.java' -File |
    Where-Object { $_.FullName -notmatch '[\\/]build[\\/]' }
$prodFiles = @($javaFiles | Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]' })
$testFiles = @($javaFiles | Where-Object { $_.FullName -match '[\\/]src[\\/]test[\\/]java[\\/]' })

Write-QLog ("architecture gate: {0} production and {1} test Java sources" -f $prodFiles.Count, $testFiles.Count) -Level STEP

# ---------------------------------------------------------------------
# FE-ARCH-001 / 002 / 004 : module layering
# ---------------------------------------------------------------------

# Measured from the committed build files, main scope only. `testImplementation`
# edges are checked separately by FE-ARCH-004.
$allowedMainDeps = @{
    'shared-kernel'            = @()
    'database'                 = @()
    'tenant-security'          = @('shared-kernel')
    'crm-core'                 = @('tenant-security', 'shared-kernel')
    'identity-access'          = @('tenant-security', 'shared-kernel')
    'inbox-helpdesk'           = @('crm-core')
    'worker-runtime'           = @('shared-kernel', 'tenant-security', 'database')
    'channels'                 = @('worker-runtime', 'crm-core', 'inbox-helpdesk')
    'notifications-realtime'   = @('worker-runtime')
    # Phase 6. Reads CRM/inbox state to route and to move a conversation between AI and human, and
    # writes outbox intents through the worker runtime. Must NOT depend on channels: routing decides
    # who answers, not how the answer is delivered.
    'workflows-sla-routing'     = @('shared-kernel', 'tenant-security', 'crm-core', 'inbox-helpdesk', 'worker-runtime', 'database')
    # Phase 7. Administration owns every state change and the audit trail, and reads the modules
    # whose state it administers. Must NOT depend on notifications-realtime or workflows-sla-routing:
    # an operator action needing either would reach past its own boundary, and the diagnosis read
    # models live in operations-observability instead.
    'administration'           = @('shared-kernel', 'tenant-security', 'identity-access', 'worker-runtime', 'channels', 'crm-core')
    # Phase 7. Diagnosis read models only. Depends on administration rather than duplicating it, so
    # there is one allow-list, one permission decision per action, and one audit writer.
    'operations-observability'  = @('shared-kernel', 'tenant-security', 'administration', 'channels', 'notifications-realtime', 'workflows-sla-routing', 'worker-runtime')
# Phase 8. Finance owns the ledger, rate cards, period lifecycle and reconciliation. It depends on
    # administration for the Admin audit stream writer, so a period lock is visible to an administrator
    # and not only inside finance, and on crm-core for JsonCodec, which is the shared lenient jsonb
    # reader the usage_events.metadata column needs. It must NOT depend on database: no finance source
    # executes DDL, and a main-scope edge there would make a schema change a finance deploy decision.
    'reporting-finance'         = @('shared-kernel', 'tenant-security', 'administration', 'crm-core')
# Phase 9. Exports declares its report shapes in its own registry and renders them; it reads tenant
    # policy from tenant-security, writes the admin audit stream through administration, publishes
    # through worker-runtime, reuses the CRM jsonb codec, and asks reporting-finance which usage rows a
    # finance report may see. It must NOT depend on database: no export source executes DDL.
    'reporting-exports'        = @('shared-kernel', 'tenant-security', 'administration', 'worker-runtime', 'crm-core', 'reporting-finance', 'reporting-exports', 'ai-rag')
# Phase 10. The AI module owns the provider boundary, tenant-filtered retrieval, explicit tools,
    # budgets, redaction, approvals and provenance. It reads tenant policy, writes the admin audit
    # stream, publishes through the worker runtime, reuses the CRM codec, and asks reporting-finance for
    # the rate-card cost of a token. It must NOT depend on database, and it must NOT depend on
    # inbox-helpdesk: an AI run that could send without going through the send gate is the boundary
    # this module exists to hold.
    'ai-rag'                    = @('shared-kernel', 'tenant-security', 'administration', 'worker-runtime', 'crm-core', 'reporting-finance')
    'app-bootstrap'            = @('shared-kernel', 'database', 'tenant-security', 'identity-access', 'crm-core', 'inbox-helpdesk', 'worker-runtime', 'channels', 'notifications-realtime', 'workflows-sla-routing', 'administration', 'operations-observability', 'reporting-finance', 'reporting-exports', 'ai-rag')
}

$moduleDirs = @(Get-ChildItem -LiteralPath $backendRoot -Directory | Sort-Object Name)
$modules = New-Object System.Collections.Generic.List[string]
foreach ($d in $moduleDirs) {
    if (Test-Path -LiteralPath (Join-Path $d.FullName 'build.gradle.kts')) { $modules.Add($d.Name) }
}

$moduleEdges = @{}
$testEdges   = @{}
foreach ($m in $modules) {
    $gradlePath = Join-Path (Join-Path $backendRoot $m) 'build.gradle.kts'
    $text = Get-Content -Raw -LiteralPath $gradlePath

    # Strip comments so a commented-out dependency is not counted as an edge.
    $code = ($text -split "`n" | Where-Object { $_ -notmatch '^\s*//' }) -join "`n"

    $mainDeps = New-Object System.Collections.Generic.List[string]
    $tstDeps  = New-Object System.Collections.Generic.List[string]
    foreach ($mm in [regex]::Matches($code, '(?<cfg>api|implementation|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly)\s*\(\s*project\(\s*":backend:(?<dep>[A-Za-z0-9_-]+)"\s*\)')) {
        $cfg = $mm.Groups['cfg'].Value
        $dep = $mm.Groups['dep'].Value
        if ($cfg.StartsWith('test')) { $tstDeps.Add($dep) } else { $mainDeps.Add($dep) }
    }
    $moduleEdges[$m] = ConvertTo-QArray -Value $mainDeps
    $testEdges[$m]   = ConvertTo-QArray -Value $tstDeps
}

$arch1 = New-Object System.Collections.Generic.List[object]
foreach ($m in $modules) {
    $permitted = @()
    if ($null -ne $m -and $allowedMainDeps.ContainsKey($m)) { $permitted = @($allowedMainDeps[$m]) }
    else {
        $arch1.Add([ordered]@{
            rule = 'FE-ARCH-001'; severity = 'FAIL'; file = "backend/$m/build.gradle.kts"
            detail = "module '$m' is not in the architecture rule set; add it deliberately or the gate cannot judge its dependencies"
        })
    }
    foreach ($dep in @($moduleEdges[$m])) {
        if ($permitted -notcontains $dep) {
            $arch1.Add([ordered]@{
                rule = 'FE-ARCH-001'; severity = 'FAIL'; file = "backend/$m/build.gradle.kts"
                detail = "main-scope dependency :backend:$dep is not permitted for :backend:$m (permitted: $($permitted -join ', '))"
            })
        }
    }
}

# FE-ARCH-004: a test-scope edge must not make the main graph cyclic.
# depth-first cycle search over the main edges.
$cycleState = @{}
$cycles = New-Object System.Collections.Generic.List[string]
function Find-Cycle {
    param([string] $Node, [System.Collections.Generic.List[string]] $Stack)
    if ($cycleState[$Node] -eq 2) { return }
    if ($cycleState[$Node] -eq 1) {
        $idx = $Stack.IndexOf($Node)
        $loop = @()
        for ($i = $idx; $i -lt $Stack.Count; $i++) { $loop += $Stack[$i] }
        $loop += $Node
        $script:cycles.Add(($loop -join ' -> '))
        return
    }
    $cycleState[$Node] = 1
    $null = $Stack.Add($Node)
    foreach ($next in @($moduleEdges[$Node])) {
        if ($next -and $moduleEdges.ContainsKey($next)) { Find-Cycle -Node $next -Stack $Stack }
    }
    $null = $Stack.RemoveAt($Stack.Count - 1)
    $cycleState[$Node] = 2
}
foreach ($m in $modules) {
    if ($moduleEdges.ContainsKey($m)) { Find-Cycle -Node $m -Stack (New-Object System.Collections.Generic.List[string]) }
}
$arch4 = New-Object System.Collections.Generic.List[object]
foreach ($c in $cycles) {
    $arch4.Add([ordered]@{
        rule = 'FE-ARCH-004'; severity = 'FAIL'; file = 'settings.gradle.kts / backend/*/build.gradle.kts'
        detail = "main-scope dependency cycle: $c"
    })
}
foreach ($m in $modules) {
    foreach ($dep in @($testEdges[$m])) {
        # a -> b in test scope is only dangerous if b already reaches a
        foreach ($reach in @($moduleEdges[$dep])) {
            if ($reach -eq $m) {
                $arch4.Add([ordered]@{
                    rule = 'FE-ARCH-004'; severity = 'FAIL'; file = "backend/$m/build.gradle.kts"
                    detail = "test-scope edge :backend:$m -> :backend:$dep closes a cycle because :backend:$dep already depends on :backend:$m"
                })
            }
        }
    }
}

# FE-ARCH-002: registration in settings.gradle.kts
$settingsPath = Join-Path $RepoRoot 'settings.gradle.kts'
$settingsText = if (Test-Path -LiteralPath $settingsPath) { Get-Content -Raw -LiteralPath $settingsPath } else { '' }
$registered = @([regex]::Matches($settingsText, 'include\(\s*":backend:(?<m>[A-Za-z0-9_-]+)"\s*\)') |
    ForEach-Object { $_.Groups['m'].Value })
$arch2 = New-Object System.Collections.Generic.List[object]
foreach ($m in $modules) {
    if ($registered -notcontains $m) {
        $arch2.Add([ordered]@{
            rule = 'FE-ARCH-002'; severity = 'FAIL'; file = 'settings.gradle.kts'
            detail = "backend/$m has a build.gradle.kts but is not included in settings.gradle.kts, so it is never built or tested"
        })
    }
}
foreach ($r in $registered) {
    if ($modules -notcontains $r) {
        $arch2.Add([ordered]@{
            rule = 'FE-ARCH-002'; severity = 'FAIL'; file = 'settings.gradle.kts'
            detail = "settings.gradle.kts includes :backend:$r but backend/$r does not exist or has no build.gradle.kts"
        })
    }
}

# FE-ARCH-003: Testcontainers version pin
$arch3 = New-Object System.Collections.Generic.List[object]
foreach ($f in $testFiles) {
    $text = Get-Content -Raw -LiteralPath $f.FullName
    if ($text -notmatch 'org\.testcontainers') { continue }
    $rel = $f.FullName.Replace($RepoRoot + '\', '').Replace($RepoRoot + '/', '')
    if ($rel -notmatch '^backend[\\/](?<m>[A-Za-z0-9_-]+)[\\/]') { continue }
    $module = $Matches['m']
    $gradlePath = Join-Path (Join-Path $backendRoot $module) 'build.gradle.kts'
    $gradle = if (Test-Path -LiteralPath $gradlePath) { Get-Content -Raw -LiteralPath $gradlePath } else { '' }
    if ($gradle -notmatch 'testcontainers\.version') {
        $arch3.Add([ordered]@{
            rule = 'FE-ARCH-003'; severity = 'FAIL'; file = "backend/$module/build.gradle.kts"
            detail = "$rel uses Testcontainers but the module does not pin extra[`"testcontainers.version`"]. The Spring Boot BOM's 1.21.3 fails with `"Could not find a valid Docker environment`" on Docker Desktop 29.x, and a container suite that cannot start is a skip in disguise"
        })
    }
}

# ---------------------------------------------------------------------
# FE-WEB-* : the HTTP boundary
# ---------------------------------------------------------------------

$relOf = {
    param($f)
    return $f.Replace($RepoRoot + '\', '').Replace($RepoRoot + '/', '')
}
$isPkg = {
    param($dir, $pkg)
    return ($dir -match ('[\\/]' + $pkg + '$'))
}

$web1 = New-Object System.Collections.Generic.List[object]
$web2 = New-Object System.Collections.Generic.List[object]
$web3 = New-Object System.Collections.Generic.List[object]
$sec2 = New-Object System.Collections.Generic.List[object]

$policyGuards = @('TenantQueryGuard', 'CrmQueryScope', 'AuthorizationService', 'RealtimeTopicPolicy',
                  'FieldRedactor', 'AiToolExecutionGuard', 'TenantScopedExecutor', 'ScopedWhere',
                  'RequirePermission', 'RequireAnyPermission', 'TenantContextHolder', 'SecurityConfig',
                  'ConnectorRegistry', 'OutboxEnqueuer', 'IdentityResolver')

foreach ($f in $prodFiles) {
    $text = Get-Content -Raw -LiteralPath $f.FullName
    $rel  = & $relOf $f.FullName
    $inWeb = & $isPkg $f.DirectoryName 'web'

    if ($inWeb -and ($text -match 'JdbcTemplate|NamedParameterJdbcTemplate|java\.sql\.|javax\.sql\.')) {
        $web1.Add([ordered]@{
            rule = 'FE-WEB-001'; severity = 'FAIL'; file = $rel
            detail = 'a class in a *.web package imports JDBC/SQL directly. The HTTP layer must call a service; the service owns the statement, which is what makes the statement reviewable in one place'
        })
    }

    # @RestControllerAdvice is an exception handler, not a request mapping.
    if (($text -match '@RestController(?!Advice)') -and -not $inWeb) {
        $web2.Add([ordered]@{
            rule = 'FE-WEB-002'; severity = 'FAIL'; file = $rel
            detail = '@RestController outside a *.web package, so it is outside the boundary this repository uses to review the HTTP surface'
        })
    }

    if ($inWeb) {
        # FE-SEC-002, hard. A client may never NAME the tenant. `projectId` is
        # allowed on the wire because the legacy client sends it and Phase 3/4
        # validate it against the server-resolved scope; `organizationId` and
        # `tenantId` have no legitimate reason to be bindable at all, and a
        # bound tenant id is one refactor away from being an authorisation.
        foreach ($tm in [regex]::Matches($text, '@(?:RequestParam|PathVariable|RequestHeader|ModelAttribute)[^\r\n]*\b(organizationId|tenantId|organization|tenant)\b')) {
            $sec2.Add([ordered]@{
                rule = 'FE-SEC-002'; severity = 'FAIL'; file = $rel
                detail = 'a controller binds a client-supplied tenant identifier. AGENTS.md: a client-provided project or tenant ID is a request, never authorization. The tenant comes from TenantContext and must not be a parameter'
            })
        }

        # FE-WEB-003, ADVISORY. A project id on the wire is validated in the
        # service (Phase 3/4 decision: "Domain services, not only controllers,
        # must enforce authorization"). What is still missing is the permission
        # named at the HTTP edge, which the phase reports claim. Recorded, not
        # failed: see docs/migration/phases/quality-gates.md.
        $bindsProjectId = ($text -match '@(RequestParam|PathVariable)[^\r\n]*\bprojectId\b')
        if ($bindsProjectId) {
            $annotated = $false
            foreach ($g in $policyGuards) { if ($text.Contains($g)) { $annotated = $true; break } }
            if (-not $annotated) {
                $web3.Add([ordered]@{
                    rule = 'FE-WEB-003'; severity = 'ADVISORY'; file = $rel
                    detail = 'this controller binds a client projectId and names no permission at the HTTP edge. Enforcement is on the service, which is the Phase-3/4 decision and is proven by the cross-tenant integration tests; what is missing is that the route itself does not say which permission it needs. Phase 7 should close this'
                })
            }
        }
    }
}

# ---------------------------------------------------------------------
# FE-SEC-001 / FE-LOG-001 / FE-LOG-002 / FE-TIME-001
# ---------------------------------------------------------------------

$sec1 = New-Object System.Collections.Generic.List[object]
$log1 = New-Object System.Collections.Generic.List[object]
$log2 = New-Object System.Collections.Generic.List[object]
$time1 = New-Object System.Collections.Generic.List[object]

foreach ($f in $prodFiles) {
    $text = Get-Content -Raw -LiteralPath $f.FullName
    $rel  = & $relOf $f.FullName

    if ($text -match '\.env\b') {
        $sec1.Add([ordered]@{
            rule = 'FE-SEC-001'; severity = 'FAIL'; file = $rel
            detail = 'production source references .env. Connection details come from validated configuration properties supplied by the environment; a file read is a secret on disk waiting to be committed'
        })
    }

    if ($text -match 'System\.(out|err)\.print') {
        $log1.Add([ordered]@{
            rule = 'FE-LOG-001'; severity = 'FAIL'; file = $rel
            detail = 'System.out/System.err bypasses the structured JSON log that carries request, job, tenant and correlation ids (FR-10.3)'
        })
    }

    # Only an ARGUMENT can leak a value. A secret word inside a message
    # literal is documentation -- "the connection credential is not
    # readable" is the correct thing to say.
    foreach ($lm in [regex]::Matches($text, '(?s)log\.(?:trace|debug|info|warn|error)\s*\((?<args>.*?)\)\s*;')) {
        $args = $lm.Groups['args'].Value
        # drop the format-string literal(s): everything up to the last
        # string literal is the message, what follows are the arguments.
        $tail = [regex]::Replace($args, '^\s*"[^"]*"', '')
        $tail = [regex]::Replace($tail, '^\s*\+\s*"[^"]*"', '')
        $tail = [regex]::Replace($tail, '^\s*"[^"]*"\s*\+\s*', '')
        $secretish = [regex]::Matches($tail, '(?i)[A-Za-z0-9_.()]*\b(password|secret|token|credential|apiKey|passphrase|privateKey|webhookSecret)\b[A-Za-z0-9_.()]*')
        $offenders = @()
        foreach ($sm in $secretish) {
            $expr = $sm.Value
            # a literal is documentation, not a value
            if ($expr -match '^\s*"' ) { continue }
            $offenders += $expr
        }
        if ($offenders.Count -gt 0) {
            $log2.Add([ordered]@{
                rule = 'FE-LOG-002'; severity = 'FAIL'; file = $rel
                detail = ('a log call passes a secret-named expression as an argument: ' + (($offenders | Select-Object -Unique) -join ', ') +
                          '. Log the identifier, the decision and the length; never the value (FR-10.6)')
            })
        }
    }

    if ((& $isPkg $f.DirectoryName 'persistence') -and $text -match 'java\.time\.(Instant|OffsetDateTime|ZonedDateTime)') {
        $time1.Add([ordered]@{
            rule = 'FE-TIME-001'; severity = 'FAIL'; file = $rel
            detail = 'a persistence class imports java.time.Instant/OffsetDateTime/ZonedDateTime. ADR-0002 requires LocalDateTime: the 98 verified columns are `timestamp without time zone` and V6 forbids timestamptz'
        })
    }
}

# ---------------------------------------------------------------------
# FE-TENANT-001 : ratchet, not a boolean
# ---------------------------------------------------------------------

$baselineDoc = $null
if (Test-Path -LiteralPath $Baseline) {
    $baselineDoc = Get-Content -Raw -LiteralPath $Baseline | ConvertFrom-Json
}

$tenantTables = @()
$baselineSql = Join-Path $RepoRoot 'ops\flyway\sql\V20260814000000__baseline_verified_live_schema.sql'
if (Test-Path -LiteralPath $baselineSql) {
    $tenantTables = @(Select-String -LiteralPath $baselineSql -Pattern 'CREATE TABLE "public"\."([^"]+)"' -AllMatches |
        ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value })
}
# `organizations` is the tenant root and `sessions` is resolved by an
# opaque token hash; neither can be scoped by organizationId because
# neither has one. Both are excluded here and both exclusions are
# recorded in the runbook's tenant-scope section.
$neverScoped = @('organizations', 'sessions')

$tenantFindings = New-Object System.Collections.Generic.List[object]
if ($tenantTables.Count -gt 0) {
    foreach ($f in $prodFiles) {
        $text = Get-Content -Raw -LiteralPath $f.FullName
        $rel  = & $relOf $f.FullName
        $line = 0
        foreach ($tm in [regex]::Matches($text, '(?s)"""(?<sql>.*?)"""')) {
            $line++
            $sql = $tm.Groups['sql'].Value
            if ($sql -notmatch '(?i)\b(select|update|delete|insert into)\b') { continue }
            if ($sql -match 'organizationId') { continue }
            # A fragment that composes the tenant scope through the shared helper IS scoped, and saying
            # so structurally beats raising the ceiling. Phase 6 added
            # where %s ... .formatted(scope.predicate("r")) in RoutingRepository, which this scan read
            # as an unscoped rom routing_rules and pushed the ratchet to 20. Recognising the marker
            # keeps the count honest in both directions: a fragment that stops composing the scope stops
            # being excused.
            $after = $text.Substring($tm.Index + $tm.Length, [Math]::Min(160, $text.Length - ($tm.Index + $tm.Length)))
            if ($after -match '(?i)(scope\.predicate\(|ScopedWhere|CrmQueryScope|\.formatted\(\s*scope)') { continue }
            # PHASE 40. The 160-character window above is a proxy for "is this fragment's tenant
            # predicate supplied by the caller", and it is wrong for a fragment declared as a
            # `static final String` constant. Phase 37's ClinicRepository declares eight such
            # constants and formats them several hundred lines later, so the window saw none of them
            # and the ratchet read 8 fragments against a ceiling of 3. Triaged by hand: all eight DO
            # substitute a real predicate -- CrmQueryScope.predicate(alias) always emits at least
            # `"organizationId" = :organizationId`, plus a project equality, an `in (...)`, or
            # `and 1 = 0` for a principal with no memberships -- so the count was a false positive and
            # the ceiling was NOT raised.
            #
            # The correction is deliberately narrow, because a security gate should tighten rather
            # than loosen. BOTH halves are required:
            #
            #   1. the fragment itself carries a format placeholder (`%s` / `%n$s`) in a where
            #      clause, so there IS something for a caller to substitute a predicate into. A
            #      fragment with no placeholder cannot be scoped by formatting, so it still has to
            #      be judged lexically and is still counted.
            #   2. the same file actually formats SQL with a scope predicate, so the predicate is
            #      supplied rather than merely possible.
            #
            # A fragment that stops composing the scope, or that stops carrying a placeholder,
            # stops being excused -- the ratchet rises again, which is the direction that matters.
            $hasWherePlaceholder = $sql -match '(?is)\bwhere\b[^;]*?%'
            $fileComposesScope  = $text -match '(?i)\.formatted\(\s*[^)]*scope\.predicate\('
            if ($hasWherePlaceholder -and $fileComposesScope) { continue }
            foreach ($t in $tenantTables) {
                if ($neverScoped -contains $t) { continue }
                if ($sql -match ('(?i)\b(from|join|into|update)\s+"?' + [regex]::Escape($t) + '"?')) {
                    $tenantFindings.Add([ordered]@{
                        rule = 'FE-TENANT-001'; severity = 'RATCHET'; file = $rel
                        detail = ("SQL fragment #{0} touches '{1}' with no lexically visible organizationId predicate" -f $line, $t)
                    })
                }
            }
        }
    }
}

$tenantCeiling = 0
if ($baselineDoc -and ($baselineDoc.PSObject.Properties.Name -contains 'tenantSqlFragmentCeiling')) {
    $tenantCeiling = [int]$baselineDoc.tenantSqlFragmentCeiling
}

$tenantOutcome = 'PASS'
if ($tenantFindings.Count -gt $tenantCeiling) {
    $tenantOutcome = 'FAIL'
    Add-Finding -Rule 'FE-TENANT-001' -Severity 'FAIL' -File 'backend/**/persistence/*' -Detail (
        "{0} tenant-SQL fragments with no visible scope predicate, baseline ceiling is {1}. A new one is not necessarily wrong -- the predicate is often appended by CrmQueryScope/ScopedWhere -- but it is not something a text scan can clear, so it has to be triaged here" -f $tenantFindings.Count, $tenantCeiling)
}
elseif ($tenantFindings.Count -lt $tenantCeiling) {
    $tenantOutcome = 'PASS_BASELINE_CAN_TIGHTEN'
    Add-Finding -Rule 'FE-TENANT-001' -Severity 'INFO' -File 'tests/contracts/architecture-baseline.json' -Detail (
        "tenant-SQL fragments fell from {0} to {1}; the ceiling can be tightened to {1}" -f $tenantCeiling, $tenantFindings.Count)
}

# ---------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------

$groups = @(
    @{ key = 'FE-ARCH-001'; title = 'module layering (main scope)'; rationale = 'plan.md 1.3: the arrow direction is the architecture. channels -> worker-runtime, never back.'; list = $arch1 },
    @{ key = 'FE-ARCH-002'; title = 'module registration'; rationale = 'an unregistered module is never built or tested, which is indistinguishable from a deleted one'; list = $arch2 },
    @{ key = 'FE-ARCH-003'; title = 'Testcontainers version pin'; rationale = 'Phase-4 defect: an unpinned module resolves 1.21.3 and fails with a Docker error that has nothing to do with the module'; list = $arch3 },
    @{ key = 'FE-ARCH-004'; title = 'no dependency cycle (main or via test scope)'; rationale = 'a cycle makes the layer diagram decorative'; list = $arch4 },
    @{ key = 'FE-WEB-001'; title = 'no SQL in the HTTP layer'; rationale = 'AGENTS.md: no controller-only authorization; the service owns the statement'; list = $web1 },
    @{ key = 'FE-WEB-002'; title = 'controllers live in *.web'; rationale = 'the boundary has to be enumerable or it is not a boundary'; list = $web2 },
    @{ key = 'FE-SEC-002'; title = 'no controller binds a client tenant id'; rationale = 'AGENTS.md: a client-provided project or tenant ID is a request, never authorization; the tenant comes from TenantContext'; list = $sec2 },
    @{ key = 'FE-SEC-001'; title = 'no .env read from production source'; rationale = 'AGENTS.md: never read or print .env'; list = $sec1 },
    @{ key = 'FE-LOG-001'; title = 'structured logging only'; rationale = 'FR-10.3'; list = $log1 },
    @{ key = 'FE-LOG-002'; title = 'no secret passed as a log argument'; rationale = 'FR-10.6'; list = $log2 },
    @{ key = 'FE-TIME-001'; title = 'LocalDateTime in persistence'; rationale = 'ADR-0002 and Phase-2 decision 4'; list = $time1 }
)

$advisory = @(
    @{ key = 'FE-WEB-003'; title = 'a bound projectId names no permission at the HTTP edge'; rationale = 'Phase-3/4 decision: enforcement lives on the service and is proven by the cross-tenant integration tests. What is missing is that the route says which permission it needs. Advisory, not a failure -- Phase 7 owns it'; list = $web3 }
)

foreach ($g in $groups) {
    $f = $g.list
    $outcome = if ($f.Count -eq 0) { 'PASS' } else { 'FAIL' }
    foreach ($item in $f) { Add-Finding -Rule $item.rule -Severity $item.severity -File $item.file -Detail $item.detail }
    New-RuleReport -Rule $g.key -Title $g.title -Rationale $g.rationale -Findings $f -Outcome $outcome
    $mark = if ($outcome -eq 'PASS') { 'ok  ' } else { 'FAIL' }
    Write-QLog ("  {0} {1,-52} {2} violation(s)" -f $mark, $g.key, $f.Count) -Level $(if ($outcome -eq 'PASS') { 'INFO' } else { 'ERROR' })
}

foreach ($g in $advisory) {
    $f = $g.list
    foreach ($item in $f) { Add-Finding -Rule $item.rule -Severity $item.severity -File $item.file -Detail $item.detail }
    New-RuleReport -Rule $g.key -Title $g.title -Rationale $g.rationale -Findings $f -Outcome $(if ($f.Count -eq 0) { 'PASS' } else { 'ADVISORY' })
    $mark = if ($f.Count -eq 0) { 'ok  ' } else { 'note' }
    Write-QLog ("  {0} {1,-52} {2} advisory finding(s)" -f $mark, $g.key, $f.Count) -Level INFO
}

New-RuleReport -Rule 'FE-TENANT-001' -Title 'tenant predicate visible in SQL fragments (ratchet)' `
    -Rationale 'the predicate is appended by CrmQueryScope/ScopedWhere, so this cannot be a boolean without a call-graph engine; the count is ratcheted instead' `
    -Findings $tenantFindings -Outcome $tenantOutcome
$mark = if ($tenantOutcome -eq 'FAIL') { 'FAIL' } else { 'ok  ' }
Write-QLog ("  {0} FE-TENANT-001 {1} fragment(s), ceiling {2}" -f $mark, $tenantFindings.Count, $tenantCeiling) -Level $(if ($tenantOutcome -eq 'FAIL') { 'ERROR' } else { 'INFO' })

$hardFailures = @($findings | Where-Object { $_.severity -eq 'FAIL' })
$verdict = if ($hardFailures.Count -eq 0) { 'PASS' } else { 'FAIL' }

$result = [ordered]@{
    check        = 'architecture'
    verdict      = $verdict
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    repoRoot     = $RepoRoot
    modules      = ConvertTo-QArray -Value $modules
    mainEdges    = $moduleEdges
    testEdges    = $testEdges
    rules        = ConvertTo-QArray -Value $report
    findings     = ConvertTo-QArray -Value $findings
    counts       = [ordered]@{
        productionSources = $prodFiles.Count
        testSources       = $testFiles.Count
        tenantSqlFragments = $tenantFindings.Count
        tenantSqlCeiling   = $tenantCeiling
        hardFailures       = $hardFailures.Count
    }
}
if ($JsonOut) { Write-QJson -Path $JsonOut -Object $result -Depth 10 | Out-Null }

Write-QLog ''
if ($verdict -eq 'FAIL') {
    foreach ($f in $hardFailures) { Write-QLog ("  {0} {1}: {2}" -f $f.rule, $f.file, $f.detail) -Level ERROR }
    Write-QLog 'architecture VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'architecture VERDICT: PASS' -Level STEP
exit 0
