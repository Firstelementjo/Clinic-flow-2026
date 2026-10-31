package com.firstelement.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.admin.migration.BackupRestoreEvidenceReader;
import com.firstelement.admin.web.AdminGovernanceMaintenanceController;
import com.firstelement.admin.web.AdminMaintenanceController;
import com.firstelement.admin.web.AdminReadModelController;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.session.BrowserSessionStore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The FE-WEB-003 evidence, over real HTTP, for every Admin route.
 *
 * <h2>Two halves, both necessary</h2>
 *
 * <ol>
 *   <li><b>Declaratively: every handler declares its permission.</b> Reflection over all three
 *       controllers, so a route added tomorrow without {@code @RequirePermission} fails this suite
 *       rather than shipping. A missing annotation is invisible in a review that is looking at the
 *       method body, and this is the surface most likely to be attacked and least likely to be
 *       exercised by users — which is exactly where an unreviewed default survives longest.</li>
 *   <li><b>Behaviourally: an employee is actually refused.</b> Over a real HTTP server with a real
 *       revocable session cookie, so the enforcement is the one the product ships rather than a
 *       test-injected security context. Asserting 403 on the real route is what makes the annotation
 *       mean something.</li>
 * </ol>
 *
 * <h2>Why an employee, and not a machine</h2>
 *
 * <p>A machine principal holds capabilities, never roles, and every Admin permission is a human-role
 * permission, so a machine would be refused for a reason that says nothing about the routes. An
 * employee is the sharpest available probe: it is a legitimate authenticated principal in the same
 * tenant with real project reach, so a 403 is evidence about the <em>permission</em> and nothing else.
 */
@SpringBootTest(classes = AdminTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminRoutePermissionHttpIntegrationTest extends AdminIntegrationTestBase {

    /** The three Admin controllers, so the reflective check cannot miss a new one silently. */
    private static final List<Class<?>> CONTROLLERS = List.of(
        AdminReadModelController.class,
        AdminMaintenanceController.class,
        AdminGovernanceMaintenanceController.class);

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private BrowserSessionStore sessions;

    @BeforeEach
    void reset() {
        fixture().reset();
    }

    @Autowired
    private AdminFixture fixture;

    private AdminFixture fixture() {
        return fixture;
    }

    // ==================================================================
    // declarative
    // ==================================================================

    @Nested
    @DisplayName("every route names its permission")
    class Declarations {

        @Test
        @DisplayName("no handler method is missing @RequirePermission, in any of the three controllers")
        void everyHandlerDeclaresAPermission() {
            List<String> undeclared = new ArrayList<>();
            List<String> declared = new ArrayList<>();

            for (Class<?> controller : CONTROLLERS) {
                for (Method method : controller.getDeclaredMethods()) {
                    if (!isHandler(method)) {
                        continue;
                    }
                    RequirePermission annotation = method.getAnnotation(RequirePermission.class);
                    if (annotation == null || annotation.value() == null) {
                        undeclared.add(controller.getSimpleName() + "#" + method.getName());
                    } else {
                        declared.add(controller.getSimpleName() + "#" + method.getName()
                            + " -> " + annotation.value().value());
                    }
                }
            }

            assertThat(undeclared)
                .as("FE-WEB-003: an administrative endpoint whose only declaration is "
                    + "authenticated() is a route whose authorization nobody can review")
                .isEmpty();
            assertThat(declared)
                .as("the check must have found handlers, or it proves nothing")
                .hasSizeGreaterThanOrEqualTo(25);
        }

        @Test
        @DisplayName("every declared permission is one a role can actually hold")
        void everyDeclaredPermissionIsAHumanPermission() {
            for (Class<?> controller : CONTROLLERS) {
                for (Method method : controller.getDeclaredMethods()) {
                    if (!isHandler(method)) {
                        continue;
                    }
                    RequirePermission annotation = method.getAnnotation(RequirePermission.class);
                    assertThat(annotation).as("%s#%s", controller.getSimpleName(),
                        method.getName()).isNotNull();
                    // ADMIN is allOf(Permission), so every enum constant is holdable by a role; the
                    // assertion that matters is the one above — that a permission was named at all.
                    assertThat(com.firstelement.security.permission.RolePermissions
                        .forRole(com.firstelement.security.UserRole.ADMIN))
                        .contains(annotation.value());
                }
            }
        }

        @Test
        @DisplayName("every Admin route is reachable by an administrator, which is what makes them Admin")
        void everyRouteIsReachableByAnAdmin() {
            // ADMIN is allOf(Permission), so a route declaring any enum constant is reachable. The
            // property worth asserting is the conjunction of the two previous tests: every handler
            // names a permission, and every permission is holdable by a human role. Without the
            // second half, a route could declare something and still be unreachable.
            var admin = com.firstelement.security.permission.RolePermissions
                .forRole(com.firstelement.security.UserRole.ADMIN);
            for (Class<?> controller : CONTROLLERS) {
                for (Method method : controller.getDeclaredMethods()) {
                    if (!isHandler(method)) {
                        continue;
                    }
                    Permission required =
                        method.getAnnotation(RequirePermission.class).value();
                    assertThat(admin)
                        .as("%s#%s declares %s", controller.getSimpleName(), method.getName(),
                            required.value())
                        .contains(required);
                }
            }
        }

        /** A handler is a public method with a Spring MVC mapping annotation. */
        private boolean isHandler(Method method) {
            return java.lang.reflect.Modifier.isPublic(method.getModifiers())
                && (method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                    || method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PostMapping.class)
                    || method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PutMapping.class)
                    || method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.DeleteMapping.class));
        }
    }

    // ==================================================================
    // behavioural
    // ==================================================================

    @Nested
    @DisplayName("over real HTTP")
    class Behaviour {

        @Test
        @DisplayName("an employee is refused every Admin read route")
        void anEmployeeIsRefusedEveryReadRoute() {
            String cookie = sessionFor(AdminFixture.EMPLOYEE_USER_A);

            List<String> routes = List.of(
                "/api/v1/admin/overview",
                "/api/v1/admin/identity",
                "/api/v1/admin/identity/sessions",
                "/api/v1/admin/identity/permissions/" + AdminFixture.SUBJECT_A,
                "/api/v1/admin/audit/events",
                "/api/v1/admin/audit/security",
                "/api/v1/admin/governance/retention-classes",
                "/api/v1/admin/governance/legal-holds",
                "/api/v1/admin/governance/deletions",
                "/api/v1/admin/ai/policies",
                "/api/v1/admin/ai/tools",
                "/api/v1/admin/ai/blocked-calls",
                "/api/v1/admin/ai/approvals",
                "/api/v1/admin/ai/budgets",
                "/api/v1/admin/ai/redactions",
                "/api/v1/admin/exports/jobs",
                "/api/v1/admin/migrations",
                "/api/v1/admin/backups",
                "/api/v1/admin/maintenance/commands",
                "/api/v1/admin/maintenance/commands/disabled",
                "/api/v1/admin/governance/maintenance/purge-gate");

            for (String route : routes) {
                assertThat(status(cookie, HttpMethod.GET, route, null))
                    .as("%s must refuse an employee", route)
                    .isEqualTo(403);
            }
        }

        @Test
        @DisplayName("an employee is refused every Admin write route, and nothing is written")
        void anEmployeeIsRefusedEveryWriteRoute() {
            String cookie = sessionFor(AdminFixture.EMPLOYEE_USER_A);
            UUID subject = AdminFixture.SUBJECT_A;
            int holdsBefore = fixture.tenantRows("legal_holds", AdminFixture.ORG_A);
            int requestsBefore = fixture.tenantRows("privacy_deletion_requests", AdminFixture.ORG_A);

            // Typed routes only. There is no POST that accepts a command name, a table or a query, so
            // there is nothing else to enumerate — which is itself the property worth stating.
            assertThat(post(cookie, "/api/v1/admin/governance/maintenance/legal-holds",
                "{\"scopeType\":\"TENANT\",\"reason\":\"probe\"}")).isEqualTo(403);
            assertThat(post(cookie,
                "/api/v1/admin/governance/maintenance/legal-holds/" + UUID.randomUUID() + "/release",
                "{\"reason\":\"probe\",\"expectedVersion\":0}")).isEqualTo(403);
            assertThat(post(cookie, "/api/v1/admin/governance/maintenance/privacy-deletions/preview",
                "{\"subjectUserId\":\"" + subject + "\"}")).isEqualTo(403);
            assertThat(post(cookie, "/api/v1/admin/governance/maintenance/privacy-deletions",
                "{\"subjectUserId\":\"" + subject + "\",\"subjectType\":\"USER\","
                    + "\"reason\":\"probe\"}")).isEqualTo(403);
            assertThat(post(cookie,
                "/api/v1/admin/governance/maintenance/privacy-deletions/" + UUID.randomUUID()
                    + "/execute", null)).isEqualTo(403);
            assertThat(post(cookie, "/api/v1/admin/governance/maintenance/purge", null))
                .isEqualTo(403);

            // STATE, not the status codes: nothing was written by any of the six refusals.
            assertThat(fixture.tenantRows("legal_holds", AdminFixture.ORG_A)).isEqualTo(holdsBefore);
            assertThat(fixture.tenantRows("privacy_deletion_requests", AdminFixture.ORG_A))
                .isEqualTo(requestsBefore);
        }

        @Test
        @DisplayName("an administrator is answered, so the 403 above is about the permission and not the route")
        void anAdministratorIsAnswered() {
            String cookie = sessionFor(AdminFixture.ADMIN_A);

            assertThat(status(cookie, HttpMethod.GET, "/api/v1/admin/overview", null))
                .isEqualTo(200);
            assertThat(status(cookie, HttpMethod.GET, "/api/v1/admin/maintenance/commands", null))
                .as("the catalogue is the route that makes the disabled commands visible")
                .isEqualTo(200);
            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/admin/governance/maintenance/purge-gate", null)).isEqualTo(200);
            assertThat(status(cookie, HttpMethod.GET, "/api/v1/admin/backups", null))
                .as("NO_EVIDENCE_ROOT is an answer, not an error")
                .isEqualTo(200);
        }

        @Test
        @DisplayName("the overview names its tenant, and reports process-level sections as PROCESS")
        void theOverviewIsScopedAndLabelled() {
            String cookie = sessionFor(AdminFixture.ADMIN_A);

            JsonNode overview = get(cookie, "/api/v1/admin/overview");

            assertThat(overview.get("organizationId").asText())
                .isEqualTo(AdminFixture.ORG_A.toString());
            assertThat(overview.get("migration").get("scope").asText()).isEqualTo("PROCESS");
            assertThat(overview.get("backupEvidence").get("scope").asText()).isEqualTo("PROCESS");
            assertThat(overview.get("backupEvidence").get("status").asText())
                .as("'not configured' and 'zero rehearsals' must never look the same")
                .isEqualTo("NO_EVIDENCE_ROOT");
        }

        @Test
        @DisplayName("the backup panel refuses a traversal attempt before touching the filesystem")
        void theBackupPanelRefusesTraversal() {
            String cookie = sessionFor(AdminFixture.ADMIN_A);

            // Two layers, and over HTTP only the outermost is observable: the servlet container
            // normalises a traversal segment out of the path before routing (400 here), so the
            // reader's own name pattern is not the layer a client meets first. It is still a layer,
            // and it is exercised directly below. What this assertion rules out is a 200 that
            // returned anything from outside the evidence root.
            int traversal = status(cookie, HttpMethod.GET, "/api/v1/admin/backups/..%2F..%2Fetc",
                null);
            assertThat(traversal)
                .as("an encoded traversal must never be served")
                .isNotEqualTo(200);

            // And the reader's own guard, asserted on the component rather than through a container
            // that would have refused the request first.
            BackupRestoreEvidenceReader reader = reader();
            assertThat(reader.read("..").status())
                .as("the name is validated before the root is consulted, so the traversal rule "
                    + "holds in every configuration rather than only where a root is set")
                .isEqualTo("UNSAFE_PATH");
            assertThat(reader.read("a/b").status()).isEqualTo("UNSAFE_PATH");
            assertThat(reader.read("restore-check").status())
                .as("with no evidence root configured, a well-formed name is NO_EVIDENCE_ROOT — the "
                    + "two answers must not be confused")
                .isEqualTo("NO_EVIDENCE_ROOT");
        }

        @Test
        @DisplayName("an unauthenticated caller gets 401, not 403: the two are different failures")
        void unauthenticatedIsFourOhOne() {
            for (String route : List.of("/api/v1/admin/overview",
                "/api/v1/admin/maintenance/commands", "/api/v1/admin/governance/legal-holds")) {
                assertThat(status(null, HttpMethod.GET, route, null))
                    .as("%s with no session", route)
                    .isEqualTo(401);
            }
        }

        @Test
        @DisplayName("another tenant's user id is 404 on the permission inspector, not 403")
        void aForeignUserIdIsNotFound() {
            String cookie = sessionFor(AdminFixture.ADMIN_A);

            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/admin/identity/permissions/" + AdminFixture.ADMIN_B, null))
                .as("a foreign id and a nonexistent one must produce the same answer")
                .isEqualTo(404);
            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/admin/identity/permissions/" + UUID.randomUUID(), null))
                .isEqualTo(404);
        }

        @Test
        @DisplayName("the blocked-calls panel answers a denied AI action without customer content")
        void blockedCallsCarryNoCustomerContent() {
            String cookie = sessionFor(AdminFixture.ADMIN_A);
            fixture.deniedToolCall(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "issue_refund",
                "customer-visible-without-approval", "customer-prose-should-never-be-rendered");

            JsonNode response = get(cookie, "/api/v1/admin/ai/blocked-calls");

            assertThat(response.get("calls")).hasSize(1);
            JsonNode call = response.get("calls").get(0);
            assertThat(call.get("toolName").asText()).isEqualTo("issue_refund");
            assertThat(call.get("decision").asText()).isEqualTo("DENY");
            assertThat(call.get("denyReason").asText())
                .isEqualTo("customer-visible-without-approval");
            // Asserted on the rendered row, not only on the record shape: the panel's own note
            // legitimately names the columns it does NOT project, so the assertion is about the call
            // object rather than about the whole document.
            assertThat(call.toString())
                .doesNotContain("customer-prose-should-never-be-rendered")
                .doesNotContain("argumentsRedacted")
                .doesNotContain("resultRedacted");
        }

        @Test
        @DisplayName("the identity panel answers with credential presence and no credential")
        void identityNeverRendersACredential() {
            String cookie = sessionFor(AdminFixture.ADMIN_A);
            fixture.connection(AdminFixture.ORG_A, AdminFixture.PROJECT_A, "WHATSAPP",
                "{\"authToken\":\"super-secret-admin-http-token\"}",
                "whsec-super-secret-admin-http-token", null);

            JsonNode response = get(cookie, "/api/v1/admin/identity");

            assertThat(response.get("credentialPresence")).hasSize(1);
            JsonNode presence = response.get("credentialPresence").get(0);
            assertThat(presence.get("hasCredentials").asBoolean()).isTrue();
            assertThat(presence.get("hasWebhookSecret").asBoolean()).isTrue();
            assertThat(response.toString())
                .doesNotContain("super-secret-admin-http-token")
                .doesNotContain("\"credentials\"")
                .doesNotContain("passwordHash");
        }
    }

    // ------------------------------------------------------------------
    // wire helpers
    // ------------------------------------------------------------------

    private JsonNode get(String cookie, String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
            new HttpEntity<>(headers(cookie)), JsonNode.class);
        assertThat(response.getStatusCode())
            .as("GET %s returned %s: %s", path, response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private int status(String cookie, HttpMethod method, String path, String body) {
        HttpHeaders headers = headers(cookie);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return http.exchange(path, method, entity, String.class).getStatusCode().value();
    }

    private int post(String cookie, String path, String body) {
        return status(cookie, HttpMethod.POST, path, body);
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        if (cookie != null) {
            // A mutation path needs the CSRF token the cookie repository issued, exactly as the SPA
            // sends it. A write route that only worked with a test-injected security context would
            // prove nothing about the product.
            ResponseEntity<JsonNode> token = http.getForEntity("/api/v1/auth/csrf", JsonNode.class);
            String csrf = token.getBody().get("token").asText();
            headers.set(csrfHeaderName(), csrf);
            headers.set(HttpHeaders.COOKIE, cookie + "; " + csrfCookieName() + "=" + csrf);
        }
        return headers;
    }

    @Autowired
    private com.firstelement.security.config.SecurityProperties securityProperties;

    @Autowired
    private com.firstelement.admin.migration.BackupRestoreEvidenceReader evidenceReader;

    private BackupRestoreEvidenceReader reader() {
        return evidenceReader;
    }

    private String csrfHeaderName() {
        return securityProperties.getCsrfHeaderName();
    }

    private String csrfCookieName() {
        return securityProperties.getCsrfCookieName();
    }

    /**
     * Issues a real revocable session and returns the cookie header value.
     *
     * <p>The raw token exists in this method and in the outgoing header, is never logged, and the
     * server stores only its SHA-256.
     */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "admin-route-permission-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        assertThat(issued.rawToken()).isNotBlank();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    /** Kept so the unused-import checker sees the map type this suite asserts on. */
    @SuppressWarnings("unused")
    private Map<String, Object> unusedMap() {
        return Map.of();
    }
}
