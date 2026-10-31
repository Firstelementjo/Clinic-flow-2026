#!/usr/bin/env pwsh
# =====================================================================
# run-smoke.ps1  --  FirstElement performance smoke
#
# Records p50/p95/p99, error rate, memory, database connections and queue
# lag against a seeded synthetic dataset, and FAILS when any of them is
# unavailable or over budget.
#
# It never skips. An unreachable target, a missing container, or an
# unreadable telemetry source is a FAILURE with the exact command to fix
# it, because a performance gate that reports "skipped" turns "we do not
# know" into "it is fine".
#
# The dataset and the exact command are documented in
# tests/performance/dataset.md; the budgets come from
# PROJECT_REQUIREMENTS section 7 via tests/performance/thresholds.json.
#
# Exit codes: 0 within budget, 1 over budget / telemetry missing /
# target unreachable, 2 bad invocation.
# =====================================================================
[CmdletBinding()]
param(
    # Base URL of a BOOTED application. Required: there is no default,
    # because silently starting nothing and reporting a number is exactly
    # the failure mode this harness exists to prevent.
    [string] $TargetBaseUrl,

    # Management/actuator base URL, when it differs from the API port
    # (Phase 3 moved OpenAPI and Actuator to MANAGEMENT_PORT, default 4101).
    [string] $ManagementBaseUrl,

    # Session cookie for the authenticated scenarios. Supplied by the
    # caller; never read from .env and never logged.
    [string] $SessionCookie,

    [string] $RepoRoot,
    [string] $Thresholds,
    [string] $Report,

    [int]    $Iterations = 0,
    [int]    $Warmup = 0,

    # PostgreSQL container for connection and queue-lag telemetry.
    [string] $Container = 'firstelement-postgres',
    [string] $DbUser = 'firstelement',
    [string] $TelemetryDatabase = 'firstelement_perf_gate_check',

    [switch] $NoTelemetry
)

$ErrorActionPreference = 'Stop'
$repoForLib = $null
for ($i = 0; $i -lt 6; $i++) {
    $candidate = Join-Path $PSScriptRoot (('..' + [string][char]92) * ($i + 1))
    if (Test-Path -LiteralPath (Join-Path $candidate 'settings.gradle.kts')) { $repoForLib = $candidate; break }
}
if (-not $repoForLib) { Write-QLog 'repository root not found' -Level ERROR; exit 2 }
. (Join-Path $repoForLib ('ops' + [string][char]92 + 'quality' + [string][char]92 + 'lib' + [string][char]92 + 'quality-common.ps1'))

if (-not $RepoRoot) { $RepoRoot = $repoForLib }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $Thresholds) { $Thresholds = Join-Path $RepoRoot 'tests\performance\thresholds.json' }
if (-not (Test-Path -LiteralPath $Thresholds)) { Write-QLog ("thresholds not found: {0}" -f $Thresholds) -Level ERROR; exit 2 }
$th = Get-Content -Raw -LiteralPath $Thresholds | ConvertFrom-Json
if ($Iterations -le 0) { $Iterations = [int]$th.iterations }
if ($Warmup -lt 0) { $Warmup = [int]$th.warmup }

$problems = New-Object System.Collections.Generic.List[string]

if (-not $TargetBaseUrl) {
    Write-QLog 'MISSING -TargetBaseUrl.' -Level ERROR
    Write-QLog 'This harness never invents a target. Start the application against a disposable, seeded database and pass its base URL:' -Level ERROR
    Write-QLog '  .\tests\performance\run-smoke.ps1 -TargetBaseUrl http://localhost:8080 -Report <path>.json' -Level ERROR
    exit 1
}
$TargetBaseUrl = $TargetBaseUrl.TrimEnd('/')

# ---------------------------------------------------------------------
# 1. Reachability -- a failure here is a failure, not a skip
# ---------------------------------------------------------------------

Write-QLog ("performance smoke against {0}" -f $TargetBaseUrl) -Level STEP

$cookieJar = New-Object System.Net.CookieContainer

function Invoke-ApiRequest {
    param(
        [string] $Url,
        [string] $Method = 'GET',
        [hashtable] $Headers = @{},
        [string] $Body,
        [int] $TimeoutSec = 30
    )
    $result = [ordered]@{ ok = $false; status = 0; body = $null; error = $null }
    $response = $null
    try {
        $req = [System.Net.HttpWebRequest]::Create($Url)
        $req.Method = $Method
        $req.CookieContainer = $cookieJar
        $req.Timeout = $TimeoutSec * 1000
        $req.ReadWriteTimeout = $TimeoutSec * 1000
        $req.AllowAutoRedirect = $false
        $req.UserAgent = 'fe-perf/1.0'
        foreach ($name in @($Headers.Keys)) {
            # Restricted headers cannot be set through Headers; nothing this harness sends is one,
            # and failing loudly beats silently dropping a header that was supposed to be measured.
            $req.Headers.Add([string]$name, [string]$Headers[$name])
        }
        if ($Body) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
            $req.ContentType = 'application/json; charset=utf-8'
            $req.ContentLength = $bytes.Length
            $stream = $req.GetRequestStream()
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Close()
        }
        $response = $req.GetResponse()
    }
    catch [System.Net.WebException] {
        # A 4xx/5xx arrives HERE, and unlike Invoke-WebRequest the response is still readable.
        $response = $_.Exception.Response
        if (-not $response) { $result.error = $_.Exception.Message; return $result }
    }
    catch {
        $result.error = $_.Exception.Message
        return $result
    }

    try {
        $result.status = [int]$response.StatusCode
        $result.ok = ($result.status -ge 200 -and $result.status -lt 300)
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $result.body = $reader.ReadToEnd()
        $reader.Dispose()
    }
    catch { }
    finally {
        if ($response) { $response.Close() }
    }
    return $result
}
function Invoke-Probe {
    param(
        [string] $Url
        # PHASE 44. The management port's /actuator/metrics/** is NOT in the security config's
        # PUBLIC_PATHS -- only /actuator/health/** and /actuator/info are -- so an unauthenticated
        # telemetry read answered 401 and the stage failed on "telemetry unavailable" for a reason
        # that had nothing to do with performance.
        #
        # A CookieContainer is keyed by HOST and not by port, so the same session covers
        # localhost:4501 and localhost:4502. That is why this is a session rather than a header:
        # a header would have to be re-derived per port.
        )
    return Invoke-ApiRequest -Url $Url -Method GET -TimeoutSec 20
}

$status = Invoke-Probe -Url ($TargetBaseUrl + '/api/v1/status')
if ($status.ok -and ($status.status -eq 200)) {
    Write-QLog ('  /api/v1/status -> {0}' -f $status.status) -Level INFO
}
else {
    Write-QLog ('  /api/v1/status -> unreachable ({0})' -f $status.status) -Level ERROR
    $problems.Add(('target {0} is not answering: {1}. Start the API and pass -TargetBaseUrl.' -f $TargetBaseUrl, $status.body))
}

# Every scenario below needs a live session; the harness refuses to
# measure an authenticated endpoint anonymously and call the 401s fast.
if (-not $SessionCookie) {
    $problems.Add('-SessionCookie was not supplied. The performance targets are for AUTHENTICATED reads and mutations; measuring the 401 path would produce a fast, meaningless p95. Log in through POST /api/v1/auth/login and pass the fe_session cookie value.')
}

# ---------------------------------------------------------------------
# 1b. The session, carried by a WebSession rather than a Cookie header
# ---------------------------------------------------------------------
# PHASE 44. The first real run measured 1,400 responses that were ALL 401, at 6-43 ms each -- a
# beautifully fast, entirely meaningless p95, which is exactly what this harness's own comment above
# warns against.
#
# The cause was the mechanism, not the credential. The login cookie carries the RAW session token
# with path "/" and httpOnly, and the value was correct. But under Windows PowerShell 5.1 a
# manually-supplied `Cookie` REQUEST HEADER is unreliable -- Invoke-WebRequest manages its own
# cookie state and does not reliably honour the header it is given. So the session was present in
# the command and absent on the wire.
#
# A WebSession carrying a CookieContainer is the supported path, and it is the same one the browser
# journeys use.
# ---------------------------------------------------------------------
# 1b. The transport: a CookieContainer and one request primitive
# ---------------------------------------------------------------------
# PHASE 47. This harness spent five runs proving that PowerShell's web cmdlets were lying to it:
# a manually supplied Cookie header was ignored (reads answered 401), and -- with the cookie fixed,
# the CSRF token fetched WITH the session, both cookies confirmed present in the container, and the
# published token confirmed EQUAL to the cookie value -- an X-XSRF-TOKEN header passed through
# -Headers never reached the server, and the mutation answered 403 CSRF_TOKEN_INVALID every time.
#
# A measurement harness cannot have a transport that silently edits the request. So there is now
# ONE primitive, used by every request in this file, built on HttpWebRequest:
#   * cookies live in a real CookieContainer, so they are attached to every request by the platform
#     rather than reconstructed by PowerShell;
#   * headers are set explicitly and are observable;
#   * a non-2xx is RETURNED, not thrown, so a refusal is data and never an exception path;
#   * the body of a refusal is read from the response, which is still open.
#
# The cookie container is keyed by HOST and not by port, so the same one covers the API port and
# the management port -- which a per-request Cookie header could never have done.

if ($SessionCookie) {
    $cookieHost = ([Uri]$TargetBaseUrl).Host
    try {
        $cookieJar.Add((New-Object System.Net.Cookie('fe_session', $SessionCookie, '/', $cookieHost)))
    }
    catch {
        $problems.Add(("could not add fe_session to the cookie container for {0}: {1}" -f $cookieHost, $_.Exception.Message))
    }
}

# ---------------------------------------------------------------------
# 1c. A CSRF token, because one of the seven scenarios is a MUTATION
# ---------------------------------------------------------------------
# PHASE 44. customer-update is PATCH /api/v1/customers/{customerId}, and the filter chain CSRF-
# protects every mutation. The first real run returned 403 for that scenario and 401 for the six
# reads, and the two have different causes: the reads were not authenticated, the mutation was
# authenticated-but-unproven. Sending one header would have hidden the second behind the first.
#
# Self-describing: the token endpoint publishes the header NAME as well as the token, so this does
# not hard-code X-XSRF-TOKEN and cannot rot if the name changes.
#
# Fetched WITH the session, which is the whole point: the token is bound to the session that asked
# for it. An earlier version fetched it anonymously and then sent the token alongside a different
# session, so customer-update answered 403 CSRF_TOKEN_INVALID while the six reads worked -- two
# different mechanisms, one of which was quietly misconfigured.
$csrfHeaderName = $null
$csrfToken = $null
if ($SessionCookie) {
    $csrfProbe = Invoke-Probe -Url ($TargetBaseUrl + '/api/v1/auth/csrf')
    if ($csrfProbe.ok -and $csrfProbe.status -eq 200) {
        try {
            $parsed = $csrfProbe.body | ConvertFrom-Json
            $csrfHeaderName = [string]$parsed.headerName
            $csrfToken = [string]$parsed.token
        }
        catch { }
    }
    if (-not $csrfHeaderName) {
        $problems.Add(('could not read a CSRF token from {0}/api/v1/auth/csrf, so the mutation ' +
            'scenario would be refused with 403 CSRF_TOKEN_INVALID and its refusal would be ' +
            'indistinguishable from a slow server.') -f $TargetBaseUrl)
    }
    else {
        # PHASE 47. CookieCsrfTokenRepository compares the header against the XSRF-TOKEN COOKIE,
        # and SecurityConfiguration sets csrfRequestAttributeName(null), so the header has to equal
        # the cookie value verbatim -- meaning the cookie must be echoed back. If PowerShell did
        # not capture it, the token is perfect and the request is refused anyway. Say which cookies
        # the harness is actually carrying, because "the token looks right" and "the token arrives"
        # are different claims.
        $carried = @()
        $xsrfCookieValue = $null
        try {
            $carried = @($cookieJar.GetCookies([Uri]$TargetBaseUrl) | ForEach-Object { $_.Name })
            $c = $cookieJar.GetCookies([Uri]$TargetBaseUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
            if ($c) { $xsrfCookieValue = $c.Value }
        }
        catch { }
        Write-QLog ('  csrf header={0}; cookies carried: {1}' -f $csrfHeaderName, ($carried -join ', ')) -Level INFO
        # The token the endpoint published and the value in the cookie are two different claims, and
        # CookieCsrfTokenRepository compares the header against the COOKIE. If they differ, the header
        # is perfect and the request is still refused -- which is exactly what CSRF_TOKEN_INVALID says
        # and exactly what nothing else in this harness would have revealed.
        if ($xsrfCookieValue) {
            if ($xsrfCookieValue -eq $csrfToken) {
                Write-QLog '  csrf endpoint token MATCHES the XSRF-TOKEN cookie value' -Level INFO
            }
            else {
                Write-QLog ('  csrf endpoint token DIFFERS from the XSRF-TOKEN cookie value (endpoint {0} chars, cookie {1} chars)' -f $csrfToken.Length, $xsrfCookieValue.Length) -Level ERROR
            }
        }
        else {
            Write-QLog '  no XSRF-TOKEN cookie is being carried, so the server will mint a fresh token and refuse the header' -Level ERROR
        }
    }
}

# ---------------------------------------------------------------------
# 1d. PREFLIGHT: prove the session is honoured BEFORE measuring anything
# ---------------------------------------------------------------------
# The lesson of this whole stage, applied as a rule rather than a memory: a measurement stage must
# refuse to produce a number it cannot stand behind. If the very first authenticated read is not 2xx,
# say so and stop -- do not spend 1,400 requests establishing what one probe already established.
if ($cookieJar -and $SessionCookie -and $problems.Count -eq 0) {
    $preStatus = 0
    $preBody = $null
    try {
        $pre = Invoke-ApiRequest -Url ($TargetBaseUrl + '/api/v1/customers?limit=1') -Method GET -TimeoutSec 20
        $preStatus = [int]$pre.status
        $preBody = [string]$pre.body
    }
    catch { $preStatus = 0; $preBody = $null }
    if ($preStatus -lt 200 -or $preStatus -ge 300) {
        $problems.Add(("preflight: an authenticated read answered {0}, so the supplied session is " +
            "not being honoured and every scenario below would measure the refusal path. Check " +
            "that -SessionCookie is the RAW fe_session value (not a URL-encoded or quoted one) and " +
            "that the cookie's host matches the target.") -f $preStatus)
    }
    else {
        Write-QLog ('  preflight authenticated read -> {0}' -f $preStatus) -Level INFO
    }
}

# ---------------------------------------------------------------------
# 1e. REAL ids, discovered from the seeded dataset through the API
# ---------------------------------------------------------------------
# PHASE 44. Three scenarios carry a path TEMPLATE -- /api/v1/customers/{customerId},
# /api/v1/conversations/{conversationId}/transcript and the PATCH against {customerId} -- and the
# harness concatenated the template onto the base URL without substituting anything. It therefore
# requested a literal "{customerId}", and those scenarios answered 400 on all 200 iterations while
# measuring the VALIDATION path and reporting the result as a latency measurement.
#
# The ids cannot be hard-coded: dataset.sql uses gen_random_uuid() for every row, so they differ on
# every run and are unknowable before the seed has run. They are therefore DISCOVERED, from the same
# first-page response the preflight already fetched -- so no extra round trip for the customer --
# plus one inbox page for a conversation.
#
# Discovered rather than configured is the right way round: an id in a config file would be stale
# the moment the dataset was reseeded, and the resulting failure would read as a product 400 rather
# than as the harness mistake it is.
$pathIds = @{}
if ($problems.Count -eq 0 -and $preBody) {
    try {
        $firstCustomer = @((($preBody | ConvertFrom-Json).items))[0]
        if ($firstCustomer -and $firstCustomer.id) {
            $pathIds['customerId'] = [string]$firstCustomer.id
            if ($firstCustomer.cxId) { $pathIds['cxId'] = [string]$firstCustomer.cxId }
            if ($firstCustomer.projectId) { $pathIds['projectId'] = [string]$firstCustomer.projectId }
        }
    }
    catch { }
    try {
        $convPage = Invoke-ApiRequest -Url ($TargetBaseUrl + '/api/v1/conversations?limit=1') -Method GET -TimeoutSec 20
        $firstConversation = @((($convPage.body | ConvertFrom-Json).items))[0]
        if ($firstConversation -and $firstConversation.id) {
            $pathIds['conversationId'] = [string]$firstConversation.id
        }
    }
    catch { }

    # Every placeholder any scenario declares must have resolved. Named explicitly, because a
    # scenario that cannot resolve its own path would otherwise fail 200 times and be reported as a
    # performance result rather than as a harness defect.
    $needed = @{}
    foreach ($sc in @($th.scenarios)) {
        foreach ($m in [regex]::Matches([string]$sc.pathTemplate, '\{(\w+)\}')) {
            $needed[$m.Groups[1].Value] = $true
        }
    }
    foreach ($key in @($needed.Keys | Sort-Object)) {
        if (-not $pathIds.ContainsKey($key)) {
            $problems.Add(("the scenarios need '{{{0}}}' but no such id could be discovered from the " +
                "seeded dataset. A scenario that cannot resolve its own path would measure a 400 and " +
                "report it as latency.") -f $key)
        }
    }
    if ($problems.Count -eq 0) {
        Write-QLog ('  discovered ids: {0}' -f ((@($pathIds.Keys) | Sort-Object) -join ', ')) -Level INFO

    # PHASE 47. The server logs "Found a CSRF token in the request" and then "Invalid CSRF token",
    # so the header DOES arrive and the value it was compared against differs.
    # CookieCsrfTokenRepository compares against the cookie, so the question is whether the cookie
    # still holds what the endpoint published by the time the mutation runs. This prints both
    # fingerprints. It answered the question: they DRIFTED, which is why the mutation was refused
    # 200 times. The loop no longer uses the published value -- it reads the cookie at request time
    # -- but the comparison stays, because "the header and the cookie agree" is a claim worth
    # checking rather than assuming, and it costs one line.
    try {
        $nowCookie = $cookieJar.GetCookies([Uri]$TargetBaseUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
        $cookieTail = if ($nowCookie) { $nowCookie.Value.Substring([Math]::Max(0, $nowCookie.Value.Length - 8)) } else { '<none>' }
        $publishedTail = if ($csrfToken) { $csrfToken.Substring([Math]::Max(0, $csrfToken.Length - 8)) } else { '<none>' }
        if ($cookieTail -eq $publishedTail) {
            Write-QLog ('  csrf token is current (tail {0}); the loop will send the cookie value anyway' -f $cookieTail) -Level INFO
        }
        else {
            Write-QLog ('  the token published by /auth/csrf is STALE (published tail {0}, cookie tail {1}); the loop sends the live cookie value' -f $publishedTail, $cookieTail) -Level INFO
        }
    }
    catch { }
    }
}

# ---------------------------------------------------------------------
# 2. Latency
# ---------------------------------------------------------------------

$scenarioResults = New-Object System.Collections.Generic.List[object]
$allLatencies = New-Object System.Collections.Generic.List[double]
$totalRequests = 0
$totalErrors = 0
# The FIRST failure anywhere in the run, verbatim: scenario, method, path, status and the
# exception message. A report that says "100% of requests failed" and nothing else is not a
# diagnosis, and this is the field that turns it into one without re-running anything.
$firstFailureSample = $null

if ($problems.Count -eq 0) {
    foreach ($sc in @($th.scenarios)) {
        $lat = New-Object System.Collections.Generic.List[double]
        $errs = 0
        # Status tally, so a scenario that fails reports WHICH status it failed with.
        $statusCounts = @{}
        $budget = [double]$sc.p95BudgetMs

        # PHASE 47. A mutation needs a BODY. The harness sent none, and once CSRF stopped refusing
        # it the PATCH answered 400 MALFORMED_REQUEST_BODY -- which is the correct answer to an
        # empty body, and the reason this scenario could never have measured anything. The body is
        # declared by the scenario rather than invented here: what a mutation writes is part of
        # what is being measured, and a harness that picks its own payload is measuring a different
        # operation from the one the threshold names.
        $requestBody = $null
        if ($sc.PSObject.Properties.Name -contains 'body' -and $sc.body) {
            $requestBody = ($sc.body | ConvertTo-Json -Depth 6 -Compress)
        }

        for ($i = 0; $i -lt ($Iterations + $Warmup); $i++) {
            $isWarmup = ($i -lt $Warmup)
            $url = $TargetBaseUrl + ([string]$sc.pathTemplate)
            # Substitute the discovered ids. PHASE 44: this line used to concatenate the raw
            # template, so a scenario with a placeholder requested the literal "{customerId}" and
            # measured the server's 400 rather than the operation it was written to measure.
            foreach ($key in @($pathIds.Keys)) {
                $url = $url.Replace('{' + $key + '}', [string]$pathIds[$key])
            }
            # The session travels in the WebSession (see 1b), NOT in a Cookie header.
            $headers = @{ 'X-Requested-With' = 'fe-perf' }
            # The CSRF token travels only on a mutation, because that is the only thing it
            # authorises. Sending it on reads would prove nothing and would hide the fact that the
            # reads need a different mechanism to work at all.
            #
            # PHASE 47, and this is the actual cause of the 200x403. The token is read from the
            # cookie jar AT REQUEST TIME rather than from the value /auth/csrf published at setup,
            # because that published value goes STALE: the fingerprints drifted between the fetch
            # and the first mutation (header tail 0ed7b1dc, cookie tail 2b23d150). The server
            # compares the header against the cookie it receives, so a header carrying a superseded
            # token is refused forever, and every earlier "fix" in this area was treating the wrong
            # thing. Taking the value from the same jar that will send the cookie makes them equal
            # by construction, so the mutation can no longer drift out of agreement with itself.
            if ($sc.method -ne 'GET') {
                $live = $cookieJar.GetCookies([Uri]$url) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
                if ($live) { $headers[$csrfHeaderName] = [string]$live.Value }
                elseif ($csrfToken) { $headers[$csrfHeaderName] = $csrfToken }
            }
            try {
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $resp = Invoke-ApiRequest -Url $url -Method ([string]$sc.method) -Headers $headers -Body $requestBody
                $sw.Stop()
                $code = [int]$resp.status
                $statusCounts["$code"] = 1 + [int]$statusCounts["$code"]
                if ($code -lt 200 -or $code -ge 300) {
                    $errs++
                    # PHASE 47. The first refusal sample is recorded HERE now, not in the catch.
                    # Invoke-ApiRequest returns a refusal instead of throwing one, so the catch block
                    # -- which used to be the only place a sample could be taken -- is never entered
                    # for an HTTP error, and the report's firstFailure came back empty while the
                    # scenario plainly had 200 of them. A sample has to be taken where the status
                    # actually arrives.
                    if (-not $isWarmup -and -not $firstFailureSample) {
                        $firstFailureSample = [ordered]@{
                            scenario     = [string]$sc.name
                            method       = [string]$sc.method
                            path         = [string]$sc.pathTemplate
                            resolvedPath = $url
                            status       = $code
                            reason       = 'the API returned a non-2xx status'
                            body         = $(if ($resp.body) { ([string]$resp.body).Substring(0, [Math]::Min(400, ([string]$resp.body).Length)) } else { $null })
                            headersSent  = (@($headers.Keys) -join ', ')
                        }
                    }
                }
                elseif (-not $isWarmup) { $lat.Add([double]$sw.ElapsedMilliseconds) }
            }
            catch {
                $sw.Stop()
                # PHASE 43. Invoke-WebRequest THROWS on any non-2xx under Windows PowerShell 5.1,
                # so every refusal arrived here and $code was never assigned. The first full run of
                # this harness therefore reported "200 failed request(s) out of 200" for all seven
                # scenarios with no indication of what they failed WITH -- and it cost a full
                # ten-minute cycle per hypothesis to narrow down.
                #
                # A harness that reports a failure rate without the statuses behind it is the same
                # class of blindness as the PN-08 flag: true because it saw nothing. So the status
                # is recovered from the exception and COUNTED, and the dominant one is named in the
                # problem list -- which is what makes the next failure self-diagnosing.
                $caught = 0
                $caughtBody = $null
                try { $caught = [int]$_.Exception.Response.StatusCode } catch { $caught = 0 }
                # PHASE 47. Reading the body here needed two fallbacks and still failed, because
                # Invoke-WebRequest disposes the response stream before the catch block runs; the
                # only place PS kept it was ErrorDetails.Message. Invoke-ApiRequest does not throw
                # at all, so this path is now for TRANSPORT failures only (a refused connection, a
                # timeout), where a status is 0 and a body genuinely does not exist.
                $caughtBody = $null
                try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $caughtBody = [string]$_.ErrorDetails.Message } } catch { }
                $statusCounts["$caught"] = 1 + [int]$statusCounts["$caught"]
                if (-not $isWarmup) {
                    $errs++
                    $lat.Add([double]$sw.ElapsedMilliseconds)
                    if (-not $firstFailureSample) {
                        $firstFailureSample = [ordered]@{
                            scenario = [string]$sc.name
                            method   = [string]$sc.method
                            path     = [string]$sc.pathTemplate
                            resolvedPath = $url
                            status   = $caught
                            reason   = [string]$_.Exception.Message
                            body     = $(if ($caughtBody) { $caughtBody.Substring(0, [Math]::Min(400, $caughtBody.Length)) } else { $null })
                        }
                    }
                }
            }
        }

        $stats = Get-QStatistics -Values (ConvertTo-QArray -Value $lat)
        foreach ($v in $lat) { $allLatencies.Add([double]$v) }
        $totalRequests += ($Iterations)
        $totalErrors += $errs

        $verdict = 'PASS'
        if ($stats.count -eq 0) { $verdict = 'NO_MEASUREMENTS' }
        elseif ($errs -gt 0) { $verdict = 'FAIL' }
        elseif ($null -ne $stats.p95 -and [double]$stats.p95 -gt $budget) { $verdict = 'FAIL' }

        $scenarioResults.Add([ordered]@{
            scenario = [string]$sc.name
            method   = [string]$sc.method
            path     = [string]$sc.pathTemplate
            budgetP95Ms = $budget
            warmup   = $Warmup
            iterations = $Iterations
            errors   = $errs
            statusCounts = $statusCounts
            latencyMs = $stats
            verdict  = $verdict
        })

        Write-QLog ('  {0,-22} p50={1,7} p95={2,7} p99={3,7} ms  errors={4}  budget p95={5}  {6}' -f `
                [string]$sc.name, $stats.p50, $stats.p95, $stats.p99, $errs, $budget, $verdict) `
            -Level $(if ($verdict -eq 'PASS') { 'INFO' } else { 'ERROR' })

        if ($verdict -eq 'FAIL') {
            if ($errs -gt 0) {
                # Name the statuses, not just the count. "200 failed requests" with no status is a
                # failure report that cannot be acted on, and it is what the first real run produced.
                $tally = ($statusCounts.GetEnumerator() | Sort-Object Value -Descending |
                    ForEach-Object { "$($_.Key)x$($_.Value)" }) -join ', '
                $problems.Add(('scenario {0}: {1} failed request(s) out of {2} -- statuses {3}' -f `
                    $sc.name, $errs, $Iterations, $tally))
            }
            if ($null -ne $stats.p95 -and [double]$stats.p95 -gt $budget) {
                $problems.Add(('scenario {0}: p95 {1} ms exceeds the {2} ms budget (PROJECT_REQUIREMENTS section 7)' -f $sc.name, $stats.p95, $budget))
            }
        }
        if ($verdict -eq 'NO_MEASUREMENTS') { $problems.Add(('scenario {0} produced no successful measurement' -f $sc.name)) }
    }
}

$overall = Get-QStatistics -Values (ConvertTo-QArray -Value $allLatencies)
$errorRate = 0.0
if ($totalRequests -gt 0) { $errorRate = [double]$totalErrors / [double]$totalRequests }
if ($totalRequests -gt 0 -and $errorRate -gt [double]$th.globalAssertions.errorRateMax) {
    $problems.Add(('error rate {0:P2} exceeds the maximum {1:P2}' -f $errorRate, [double]$th.globalAssertions.errorRateMax))
}

# ---------------------------------------------------------------------
# 3. Telemetry: memory, database connections, queue lag
# ---------------------------------------------------------------------

$telemetry = [ordered]@{
    memory = $null
    databaseConnections = $null
    queueLag = $null
    unavailable = @()
}

if (-not $NoTelemetry) {
    # --- process memory (API), via the management port when exposed ---
    $mgmt = $ManagementBaseUrl
    if (-not $mgmt) { $mgmt = 'http://localhost:4101' }
    $mgmt = $mgmt.TrimEnd('/')
    $mem = Invoke-Probe -Url ($mgmt + '/actuator/metrics/jvm.memory.used')
    if ($mem.ok -and $mem.status -eq 200) {
        $values = @([regex]::Matches([string]$mem.body, '"statistic":"([^"]+)","value":([0-9.Ee+-]+)') |
            ForEach-Object { @{ statistic = $_.Groups[1].Value; bytes = [double]$_.Groups[2].Value } })
        $telemetry.memory = [ordered]@{
            source = $mgmt + '/actuator/metrics/jvm.memory.used'
            measurements = ConvertTo-QArray -Value $values
        }
        Write-QLog ('  memory: {0}' -f (($values | ForEach-Object { $_.statistic + '=' + [Math]::Round($_.bytes / 1MB, 1) + 'MB' }) -join ', ')) -Level INFO
    }
    else {
        $telemetry.unavailable += ('jvm memory: {0}/actuator/metrics/jvm.memory.used answered {1}' -f $mgmt, $mem.status)
    }

    # --- database connections and queue lag, read-only from PostgreSQL ---
    $docker = Resolve-QCommandPath -Name 'docker'
    if (-not $docker) {
        $telemetry.unavailable += 'docker is not on PATH, so pg_stat_activity cannot be read'
    }
    else {
        function Invoke-PsqlRead {
            param([string] $Sql, [string] $Db)
            $previous = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $out = ($Sql | & $docker exec -i $Container psql -U $DbUser -d $Db -t -A -P pager=off -f - 2>&1) | ForEach-Object { $_.ToString() }
            $ErrorActionPreference = $previous
            if ($LASTEXITCODE -ne 0) { throw (($out) -join ' ') }
            return $out
        }
        try {
            $connRow = (Invoke-PsqlRead -Db 'postgres' -Sql @'
select (select setting from pg_settings where name='max_connections') || '|' ||
       (select count(*) from pg_stat_activity where datname is not null);
'@) | Select-Object -First 1
            $parts = [string]$connRow -split '\|'
            $telemetry.databaseConnections = [ordered]@{
                source      = 'pg_stat_activity + pg_settings.max_connections'
                max         = [int]$parts[0]
                inUse       = [int]$parts[1]
                database    = $TelemetryDatabase
            }
            Write-QLog ('  database connections: {0} of {1} in use' -f $telemetry.databaseConnections.inUse, $telemetry.databaseConnections.max) -Level INFO
        }
        catch {
            $telemetry.unavailable += ('database connections: ' + $_.Exception.Message)
        }

        # queue lag: pending outbox rows and the age of the oldest one. The
        # table exists from the Phase-5a chain; on a pre-boot database the
        # query fails and that is recorded rather than assumed to be zero.
        try {
            $q = (Invoke-PsqlRead -Db $TelemetryDatabase -Sql @'
select count(*)::text || '|' ||
       coalesce((select extract(epoch from ((now() at time zone 'utc') - "createdAt"))::bigint::text from outbox_events where state in ('PENDING','LEASED') order by "createdAt" asc limit 1), '-1') || '|' ||
       coalesce((select count(*)::text from outbox_events where state = 'DEAD'), '0');
'@) | Select-Object -First 1
            $qp = [string]$q -split '\|'
            $oldest = [double]$qp[1]
            $telemetry.queueLag = [ordered]@{
                source          = 'outbox_events (pending, oldest-pending age, dead)'
                pending         = [int]$qp[0]
                oldestPendingSeconds = $(if ($oldest -lt 0) { $null } else { [double]$oldest })
                deadLetters     = [int]$qp[2]
            }
            Write-QLog ('  queue lag: {0} pending, oldest {1}s, {2} dead' -f $telemetry.queueLag.pending, $telemetry.queueLag.oldestPendingSeconds, $telemetry.queueLag.deadLetters) -Level INFO
        }
        catch {
            $telemetry.unavailable += ('queue lag: ' + $_.Exception.Message)
        }
    }

    if (@($telemetry.unavailable).Count -gt 0) {
        foreach ($u in $telemetry.unavailable) {
            $problems.Add(('telemetry unavailable: {0}. plan.md 7.3 requires memory, connections and queue lag to be recorded with a performance claim, so an unreadable source fails the stage.' -f $u))
        }
    }
}
else {
    $problems.Add('-NoTelemetry was passed. plan.md 7.3 requires memory, database connections and queue lag alongside any performance claim, so suppressing telemetry fails the stage.')
}

# ---------------------------------------------------------------------
# 4. Verdict
# ---------------------------------------------------------------------

$verdict = if ($problems.Count -eq 0) { 'PASS' } else { 'FAIL' }

$reportDoc = [ordered]@{
    check         = 'performance-smoke'
    verdict       = $verdict
    checkedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
    dataset       = 'tests/performance/dataset.md; seed tests/performance/dataset.sql'
    command       = ('run-smoke.ps1 -TargetBaseUrl {0} -Iterations {1} -Warmup {2} -TelemetryDatabase {3}' -f $TargetBaseUrl, $Iterations, $Warmup, $TelemetryDatabase)
    targetBaseUrl = $TargetBaseUrl
    managementBaseUrl = $ManagementBaseUrl
    container     = $Container
    iterations    = $Iterations
    warmup        = $Warmup
    overallLatencyMs = $overall
    requests      = $totalRequests
    errors        = $totalErrors
    errorRate     = $errorRate
    firstFailure  = $firstFailureSample
    scenarios     = ConvertTo-QArray -Value $scenarioResults
    telemetry     = $telemetry
    budgets       = [ordered]@{
        readP95Ms        = [double]$th.latencyBudgetsMs.authenticatedReadP95
        mutationP95Ms    = [double]$th.latencyBudgetsMs.mutationWithoutProviderP95
        firstInboxPageMs = [double]$th.latencyBudgetsMs.firstInboxPageEndToEndMs
        errorRateMax     = [double]$th.globalAssertions.errorRateMax
    }
    problems      = ConvertTo-QArray -Value $problems
    scope         = 'smoke only: sustained load, export throughput and database failover simulation belong to the phase that owns the module'
}

if ($Report) { Write-QJson -Path $Report -Object $reportDoc -Depth 10 | Out-Null }

Write-QLog ''
Write-QLog ('  overall p50={0} p95={1} p99={2} ms over {3} request(s), error rate {4:P2}' -f `
        $overall.p50, $overall.p95, $overall.p99, $totalRequests, $errorRate) -Level INFO
if ($Report) { Write-QLog ('  report: ' + $Report) -Level INFO }

if ($verdict -eq 'FAIL') {
    foreach ($p in $problems) { Write-QLog ('  - ' + $p) -Level ERROR }
    Write-QLog 'performance-smoke VERDICT: FAIL' -Level ERROR
    exit 1
}
Write-QLog 'performance-smoke VERDICT: PASS' -Level STEP
exit 0
