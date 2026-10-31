package com.firstelement.web.contract;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The refusals each operation can actually produce, derived rather than pasted.
 *
 * <h2>Why derivation is the only honest option here</h2>
 *
 * <p>Phase 18 gave every mapped failure a structured {@code ErrorResponse} with a stable code, and
 * then the document declared none of them: a generated client had no typed way to branch on a
 * refusal. The obvious repair — paste {@code 400} and {@code 404} on every operation — is a lie in
 * the other direction, and ADR 0016 rule 3's principle applies to refusals exactly as it does to
 * success codes: <b>a declared refusal a route cannot produce is the same lie.</b> So every rule
 * below is stated as a property of the route, and each one is checkable against the code.
 *
 * <h2>The rules</h2>
 *
 * <table>
 *   <caption>Refusal derivation</caption>
 *   <tr><th>Code</th><th>Status</th><th>Derived from</th></tr>
 *   <tr><td>{@code AUTHENTICATION_REQUIRED}</td><td>401</td>
 *       <td>The path is not in {@code SecurityConfiguration.PUBLIC_PATHS}. Read from that class by
 *           reflection rather than restated here, so the document cannot disagree with the filter
 *           chain it documents.</td></tr>
 *   <tr><td>{@code ACCESS_DENIED}</td><td>403</td>
 *       <td>The handler carries {@code @RequirePermission} or {@code @RequireAnyPermission}. An
 *           authenticated caller who does not hold the grant is refused before the handler
 *           method's body runs.</td></tr>
 *   <tr><td>{@code NOT_FOUND}</td><td>404</td>
 *       <td>The operation takes a path variable, so a name can name nothing — and, on a
 *           permission-guarded route, a cross-tenant id is the <em>same</em> 404, which is the
 *           non-disclosure property rather than a separate case.</td></tr>
 *   <tr><td>{@code VALIDATION_FAILED}</td><td>400</td>
 *       <td>The operation consumes a body, requires a parameter, or binds a typed path variable.
 *           Each of those has a reachable binding failure; the body's stable code is
 *           {@code VALIDATION_FAILED}, {@code MALFORMED_REQUEST_BODY} or {@code BAD_REQUEST}
 *           depending on which layer refused, and all three are this status.</td></tr>
 *   <tr><td>{@code UNSUPPORTED_MEDIA_TYPE}</td><td>415</td>
 *       <td>The operation consumes a JSON body, so a different {@code Content-Type} is refused.</td></tr>
 *   <tr><td>{@code CSRF_TOKEN_INVALID}</td><td>403</td>
 *       <td>The operation is a cookie-authenticated mutation, so a missing or stale
 *           {@code X-XSRF-TOKEN} is refused by the filter chain.</td></tr>
 * </table>
 *
 * <p>Four refusals are <b>not</b> derivable from a route's shape and are therefore declared only
 * where a service actually raises them, in {@link #EXTRA_REFUSALS}. {@code 409 VERSION_CONFLICT}
 * belongs to optimistic concurrency and {@code 422} to a permanently unsatisfiable export; neither
 * can be inferred from a signature, so listing them everywhere would be exactly the speculative
 * paste this class exists to avoid.
 *
 * <h2>Why the exclusions are named</h2>
 *
 * <p>{@link #NO_NOT_FOUND} is the honest half of this class. A rule that fires on "the operation
 * takes a path variable" is right for a route that looks a row up and wrong for one that does not,
 * and the only way to keep the rule usable is to name the exceptions and say why. Each entry below
 * is a route where the rule would over-claim, with the observed behaviour that makes it so.
 */
public final class RefusalRules {

    private RefusalRules() {
    }

    /**
     * One refusal an operation can return.
     *
     * @param status      the HTTP status
     * @param code        the stable code the body carries, or {@code "varies"} where more than one
     *                    code maps to this status on this route
     * @param description what the refusal means, in the words a client needs
     */
    public record Refusal(int status, String code, String description) {

        public Refusal {
            if (status < 400 || status > 599) {
                throw new IllegalArgumentException(
                    "a refusal is a 4xx or a 5xx; " + status + " is neither");
            }
            if (code == null || code.isBlank() || description == null || description.isBlank()) {
                throw new IllegalArgumentException(
                    "a refusal must name its stable code and say what it means; a client branches on the "
                        + "code and a human reads the meaning");
            }
        }
    }

    // ------------------------------------------------------------------
    // the shape-derived refusals
    // ------------------------------------------------------------------

    public static final Refusal UNAUTHENTICATED = new Refusal(401, "AUTHENTICATION_REQUIRED",
        "No session, an expired session, or a revoked one. The three are indistinguishable by design.");

    public static final Refusal ACCESS_DENIED = new Refusal(403, "ACCESS_DENIED",
        "Authenticated, but the caller's role does not hold the permission this route requires.");

    public static final Refusal NOT_FOUND = new Refusal(404, "NOT_FOUND",
        "No such resource in your scope. A resource belonging to another tenant is answered with "
            + "this same code and this same body, so existence is never disclosed.");

    public static final Refusal VALIDATION_FAILED = new Refusal(400, "varies",
        "The request could not be bound: a missing or unbindable parameter, an unbindable path "
            + "variable, a body that is not JSON, or a field the service refuses. The body names "
            + "which one, with the accepted values where there is a closed set.");

    public static final Refusal UNSUPPORTED_MEDIA_TYPE = new Refusal(415, "UNSUPPORTED_MEDIA_TYPE",
        "This operation consumes a JSON body and cannot accept that Content-Type. The body's "
            + "supported set is returned so the caller can discover what to send.");

    public static final Refusal CSRF_INVALID = new Refusal(403, "CSRF_TOKEN_INVALID",
        "The cookie-authenticated mutation carried no X-XSRF-TOKEN, or one the server did not "
            + "issue. Fetch /api/v1/auth/csrf and echo the token.");

    public static final Refusal VERSION_CONFLICT = new Refusal(409, "VERSION_CONFLICT",
        "Optimistic concurrency: the resource changed since the caller read it. The body's details "
            + "carry the current version so the caller can re-read and re-apply.");

    public static final Refusal EXPORT_FORMAT_UNSUPPORTED = new Refusal(422, "varies",
        "The request is well formed and permanently unsatisfiable: a filter that cannot be pushed "
            + "down, a column the registry does not declare, or a format this renderer cannot "
            + "produce. Retrying the same request cannot help.");

    public static final Refusal EXPORT_ARTEFACT_GONE = new Refusal(410, "varies",
        "The artefact existed and is gone — expired or deleted. 404 would say it never existed, "
            + "which is a different fact an operator needs to tell apart.");

    public static final Refusal LOGIN_RATE_LIMITED = new Refusal(429, "LOGIN_RATE_LIMITED",
        "Too many failed sign-in attempts from this client in the window. Retry after the interval "
            + "in the Retry-After header.");

    // ------------------------------------------------------------------
    // the exceptions, each with the observed behaviour that forces it
    // ------------------------------------------------------------------

    /**
     * Operations that take a path variable but cannot answer 404.
     *
     * <p>Each entry is a route whose path variable names something other than a row, and each is here
     * because a test says so rather than because it looked that way.
     *
     * <p>One entry is the point. A list of exceptions nobody can audit is worse than no rule, because
     * it hides the exceptions that matter inside the ones that were guessed — so this list is kept to
     * the single route with direct evidence, and an exclusion added without one is a defect in this
     * class.
     */
    public static final List<String> NO_NOT_FOUND = List.of(
        "GET /api/v1/admin/backups/{name}");

    /**
     * No {@code 500} is ever declared.
     *
     * <p>Deliberate, and worth stating because the ledger lists "zero 5xx" as part of the same defect.
     * {@code 500 INTERNAL_ERROR} is what {@code ErrorHandlingAdvice}'s catch-all answers for a fault
     * in this codebase rather than in the request. Publishing it on every operation would teach a
     * generated client to treat an outage as a normal branch with a typed body, which is how a
     * monitoring signal becomes a handled case. {@code plan.md} requires a request the server cannot
     * answer to be a refusal and never a 500, so the 500s that remain are defects to remove rather
     * than codes to document.
     */
    public static final List<Refusal> NO_SERVER_FAULTS = List.of();

    /**
     * Refusals that come from a service rather than from a route's shape.
     *
     * <p>Keyed {@code "METHOD /path"}; values are the refusals that route adds beyond the
     * shape-derived set. A route absent from this map declares only what its shape derives.
     */
    public static final java.util.Map<String, List<Refusal>> EXTRA_REFUSALS = java.util.Map.ofEntries(
        // The login route is PUBLIC_PATHS -- it is how a caller ACQUIRES a session -- and the
        // shape-derived rules therefore omit 401 from it, because "no session required" is not
        // the same claim as "cannot answer 401". It can: LoginService throws invalidCredentials()
        // for an unknown email, an inactive user and a wrong password, AuthenticationFailedException
        // renders as 401 INVALID_CREDENTIALS, and SecurityBoundaryHttpIntegrationTest asserts that
        // body. Omitting it here made the document tell a generated client that a rejected sign-in
        // is a success, which is the omission-direction twin of the ADR-0016 lie.
        java.util.Map.entry("POST /api/v1/auth/login", List.of(
            new Refusal(401, "INVALID_CREDENTIALS",
                "The email is unknown, the password is wrong, or the account is inactive. The three "
                    + "are deliberately indistinguishable: distinguishing them tells a caller which "
                    + "emails exist."),
            LOGIN_RATE_LIMITED)),
        java.util.Map.entry("POST /api/v1/auth/logout", List.of()),
        java.util.Map.entry("GET /api/v1/exports/{exportId}/download",
            List.of(EXPORT_ARTEFACT_GONE, new Refusal(401, "varies",
                "The signature is absent, malformed, or expired."))),
        java.util.Map.entry("POST /api/v1/exports", List.of(EXPORT_FORMAT_UNSUPPORTED)),
        java.util.Map.entry("POST /api/v1/exports/report-definitions/{definitionId}/versions",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("PATCH /api/v1/exports/report-definitions/{definitionId}",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("POST /api/v1/exports/report-definitions/{definitionId}/versions/{versionNumber}/publish",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("POST /api/v1/exports/report-definitions/{definitionId}/versions/{versionNumber}/retire",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("POST /api/v1/exports/document-templates/{templateId}/versions",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("POST /api/v1/exports/document-templates/{templateId}/versions/{versionNumber}/publish",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("POST /api/v1/exports/document-templates/{templateId}/versions/{versionNumber}/retire",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("PUT /api/v1/projects/{projectId}/classification-questions/{questionId}",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("POST /api/v1/admin/governance/maintenance/legal-holds/{holdId}/release",
            List.of(VERSION_CONFLICT)),
        java.util.Map.entry("POST /api/v1/finance/periods/{periodId}/lock",
            List.of(new Refusal(409, "varies",
                "The period is not in a state that can be locked, or its figures are incomplete."))),
        java.util.Map.entry("POST /api/v1/finance/periods/{periodId}/reopen",
            List.of(new Refusal(409, "varies",
                "The period was never locked, so there is nothing to reopen."))),
        java.util.Map.entry("POST /api/v1/finance/adjustments/{adjustmentId}/approve",
            List.of(new Refusal(409, "varies",
                "The adjustment is not PENDING, so it cannot be approved."))),
        java.util.Map.entry("POST /api/v1/finance/adjustments/{adjustmentId}/reject",
            List.of(new Refusal(409, "varies",
                "The adjustment is not PENDING, so it cannot be rejected."))),
        java.util.Map.entry("POST /api/v1/finance/adjustments/{adjustmentId}/apply",
            List.of(new Refusal(409, "varies",
                "The adjustment is not APPROVED, or the period is not OPEN, so it cannot be applied."))),
        java.util.Map.entry("POST /api/v1/exports/retention/purge",
            List.of(new Refusal(409, "varies",
                "The retention capability exists and is disabled. The body says what it would have done."))),
        java.util.Map.entry("POST /api/v1/admin/governance/maintenance/purge",
            List.of(new Refusal(409, "varies",
                "A legal hold covers the data, or the purge capability is disabled. The body names "
                    + "which."))));

    // ------------------------------------------------------------------
    // the security boundary, read rather than restated
    // ------------------------------------------------------------------

    /**
     * The paths the filter chain lets through without a session.
     *
     * <p>Read from {@code SecurityConfiguration} by reflection, on purpose. A copy of this list in
     * a contract class would be a second answer to a question the filter chain already answers, and
     * the two would drift exactly the way {@code openapi-paths.json} drifted in Phase 7.
     */
    public static Set<String> publicPaths() {
        try {
            Class<?> configuration = Class.forName(
                "com.firstelement.security.config.SecurityConfiguration");
            Field field = configuration.getDeclaredField("PUBLIC_PATHS");
            field.setAccessible(true);
            String[] declared = (String[]) field.get(null);
            return new LinkedHashSet<>(List.of(declared));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "RefusalRules could not read SecurityConfiguration.PUBLIC_PATHS, so it cannot tell "
                    + "which operations are reachable without a session. Declaring 401 on the login "
                    + "route, or omitting it from every other route, is the ADR-0016 defect this class "
                    + "exists to close -- so failing the document export is the honest answer.", e);
        }
    }

    /**
     * Whether a path is served without a session.
     *
     * <p>Ant-style, because the declared paths are: {@code /actuator/health/**} and
     * {@code /actuator/info}. A literal equality test would call those authenticated and put a 401
     * on a liveness probe.
     */
    public static boolean isPublic(String path) {
        for (String pattern : publicPaths()) {
            if (matches(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String antPattern, String path) {
        StringBuilder regex = new StringBuilder(antPattern.length() + 8);
        int i = 0;
        while (i < antPattern.length()) {
            char c = antPattern.charAt(i);
            if (c == '*' && i + 1 < antPattern.length() && antPattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i += 2;
                if (i < antPattern.length() && antPattern.charAt(i) == '/') {
                    i++;
                }
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if ("{}".indexOf(c) >= 0) {
                regex.append("[^/]+");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return path.matches(regex.toString());
    }

    /**
     * Every refusal an operation declares, in status order.
     *
     * @param path           the templated path
     * @param method         the upper-case HTTP method
     * @param permissionGuarded whether the handler declares a required permission
     * @param consumesBody   whether the operation binds a request body
     * @param requiredParams whether the operation requires any parameter
     * @param hasPathVariable whether any templated segment is a variable
     * @param mutation       whether the HTTP method changes state
     */
    public static List<Refusal> forOperation(String method, String path, boolean permissionGuarded,
                                             boolean consumesBody, boolean requiredParams,
                                             boolean hasPathVariable, boolean mutation) {
        String key = method + " " + path;
        Set<Refusal> derived = new LinkedHashSet<>();

        if (!isPublic(path)) {
            derived.add(UNAUTHENTICATED);
            if (permissionGuarded) {
                derived.add(ACCESS_DENIED);
            }
            if (mutation) {
                derived.add(CSRF_INVALID);
            }
        }
        if (hasPathVariable && !NO_NOT_FOUND.contains(key)) {
            derived.add(NOT_FOUND);
        }
        if (consumesBody) {
            derived.add(VALIDATION_FAILED);
            derived.add(UNSUPPORTED_MEDIA_TYPE);
        }
        if (requiredParams) {
            derived.add(VALIDATION_FAILED);
        }
        derived.addAll(EXTRA_REFUSALS.getOrDefault(key, List.of()));

        List<Refusal> ordered = new ArrayList<>(derived);
        ordered.sort(java.util.Comparator.comparingInt(Refusal::status).thenComparing(Refusal::code));
        return List.copyOf(ordered);
    }
}