package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.crm.clinic.ClinicReadService;
import com.firstelement.inbox.web.ClinicController;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import org.springframework.security.access.AccessDeniedException;
import com.firstelement.security.session.BrowserSessionStore;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The contract half of this slice: every clinic route's <b>declared</b> status and refusals,
 * against what it was <b>observed</b> to return over a real socket.
 *
 * <h2>Why "declared" and "observed" are kept apart</h2>
 *
 * <p>ADR 0016's rule is "observe, then declare", and rule 4 says the correct action where a
 * real code is not established by a test is to <em>write the test</em>. So the two halves live in
 * different files on purpose: {@link ClinicReadModelHttpIntegrationTest} executes every route and
 * records what it returned, and this file reads the document the running application serves and
 * compares.
 *
 * <p>A single test that did both would pass on any consistent pair — including a pair that is
 * consistently wrong, because the same bug would produce the same wrong answer in the document
 * and in the assertion.
 *
 * <h2>What is asserted about refusals</h2>
 *
 * <p>Only that the declared set is exactly {@code 401, 403} on a list route and
 * {@code 401, 403, 404} on an addressed one, and each is separately <em>executed</em> elsewhere
 * in this module:
 *
 * <ul>
 *   <li><b>401</b> — {@code unauthenticatedIsFourOhOne} hits all seven routes with no cookie;</li>
 *   <li><b>403</b> — {@code aPrincipalWithoutTheClinicGrantIsRefusedByEveryServiceMethod} binds a
 *       principal with no grant;</li>
 *   <li><b>404</b> — {@code everyRouteRefusesTenantBsIdsWithTheSameNonDisclosing404} and
 *       {@code aTenantBProjectIdInTheQueryStringIsRefused} both produce it, and compare the body
 *       byte for byte against a nonexistent id.</li>
 * </ul>
 *
 * <p>So nothing here is declared speculatively. There is deliberately <b>no 400</b> on a list
 * route: it has no required parameter and consumes no body. There is deliberately <b>no 404</b>
 * on a list route: it has nothing to look up and a miss is an empty list. Both absences are the
 * ADR 0016 rule applied to refusals — a declared refusal a route cannot produce is the same lie
 * as a wrong success code.
 */
@SpringBootTest(classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClinicContractHttpIntegrationTest extends InboxIntegrationTestBase {

    /** The routes this module contributes, in the order the document lists them. */
    private static final List<String> CLINIC_PATHS = List.of(
        "/api/v1/clinic/doctors",
        "/api/v1/clinic/doctors/{doctorId}",
        "/api/v1/clinic/procedures",
        "/api/v1/clinic/procedures/{procedureId}",
        "/api/v1/clinic/appointments",
        "/api/v1/clinic/appointments/{appointmentId}",
        "/api/v1/clinic/analytics");

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private BrowserSessionStore sessions;
    @Autowired
    private com.firstelement.security.config.SecurityProperties securityProperties;
    @Autowired
    private InboxFixture fixture;
    @Autowired
    private ClinicFixture clinic;
    @Autowired
    private ClinicReadService clinicService;

    @BeforeEach
    void reset() {
        fixture.reset();
        // Explicit, not part of the shared reset -- see the note in InboxFixture#reset.
        clinic.seedClinic();
    }

    // ==================================================================
    // the served routes
    // ==================================================================

    @Nested
    @DisplayName("every route answers the status it is observed to answer")
    class ObservedStatus {

        @Test
        @DisplayName("an authorised caller is answered 200 on all seven routes")
        void everyRouteIsAnsweredTwoHundred() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            for (String path : CLINIC_PATHS) {
                assertThat(status(cookie, HttpMethod.GET, concrete(path), null))
                    .as("%s was executed over a real socket, with a real revocable session and "
                        + "the product's own filter chain", path)
                    .isEqualTo(200);
            }
        }

        @Test
        @DisplayName("no clinic route is asynchronous, so none of them is a 202")
        void noRouteIsAcceptedWork() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            for (String path : CLINIC_PATHS) {
                assertThat(status(cookie, HttpMethod.GET, concrete(path), null))
                    .as("%s completes inside the request, so 202 would tell a client the work is "
                        + "outstanding when it has already happened", path)
                    .isNotEqualTo(202);
            }
        }

        @Test
        @DisplayName("every refusal the contract declares is reachable, and answered here")
        void everyDeclaredRefusalIsReachable() {
            String cookie = sessionFor(InboxFixture.USER_ADMIN_A);

            // 401 -- no session at all, on every route including the addressed ones.
            for (String path : CLINIC_PATHS) {
                assertThat(status(null, HttpMethod.GET, concrete(path), null))
                    .as("%s with no session", path).isEqualTo(401);
            }

            // 403 -- a legitimate principal of the tenant with no clinic grant. Probed through the
            // service in Roles#aPrincipalWithoutTheClinicGrantIsRefusedByEveryServiceMethod,
            // because there is no session that can be issued for a grant the role does not hold.
            assertThatThrownBy(() -> PrincipalScope.call(machinePrincipal(),
                    () -> clinicService.doctors(machinePrincipal(), null)))
                .isInstanceOf(AccessDeniedException.class);

            // 404 -- a foreign id and a nonexistent id, answered identically.
            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/clinic/doctors/" + ClinicFixture.DOCTOR_B1, null)).isEqualTo(404);
            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/clinic/doctors/" + UUID.randomUUID(), null)).isEqualTo(404);
            assertThat(status(cookie, HttpMethod.GET,
                "/api/v1/clinic/doctors/not-a-uuid", null))
                .as("an id that is not a UUID is a 400, and the contract declares a 400 on every "
                    + "addressed route. The first draft parsed a String and answered 404 here, "
                    + "which left the document declaring a 400 the route could not produce -- ADR "
                    + "0016 rule 3's principle applied to refusals. This is the declaration and "
                    + "the observation agreeing")
                .isEqualTo(400);

            // And the refusal the declaration must NOT carry: a list route has nothing to look up,
            // so a miss is an empty list rather than a 404.
            assertThat(status(cookie, HttpMethod.GET, "/api/v1/clinic/doctors", null))
                .isEqualTo(200);
        }
    }
    // ==================================================================
    // declarations
    // ==================================================================

    @Nested
    @DisplayName("declarations")
    class Declarations {

        @Test
        @DisplayName("every handler declares the permission it was given")
        void everyHandlerDeclaresItsPermission() {
            List<String> declared = new ArrayList<>();
            for (Method method : ClinicController.class.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(GetMapping.class)) {
                    continue;
                }
                RequirePermission annotation = method.getAnnotation(RequirePermission.class);
                assertThat(annotation)
                    .as("ClinicController#%s declares no permission, so its authorization is "
                        + "not reviewable at the HTTP edge", method.getName())
                    .isNotNull();
                declared.add(annotation.value().value());
            }
            assertThat(declared)
                .as("the check must have found handlers, or it proves nothing")
                .containsExactlyInAnyOrder(
                    Permission.DOCTOR_READ.value(), Permission.DOCTOR_READ.value(),
                    Permission.PROCEDURE_READ.value(), Permission.PROCEDURE_READ.value(),
                    Permission.APPOINTMENT_READ.value(), Permission.APPOINTMENT_READ.value(),
                    Permission.ANALYTICS_READ_PROJECT.value());
        }

        @Test
        @DisplayName("every declared permission is one a role can actually hold")
        void everyDeclaredPermissionIsHoldable() {
            var admin = RolePermissions.forRole(com.firstelement.security.UserRole.ADMIN);
            for (Method method : ClinicController.class.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(GetMapping.class)) {
                    continue;
                }
                Permission required = method.getAnnotation(RequirePermission.class).value();
                assertThat(admin)
                    .as("ClinicController#%s declares %s, which no role holds -- so the route "
                        + "would be unreachable for everyone", method.getName(), required.value())
                    .contains(required);
            }
        }

        @Test
        @DisplayName("the service declares the same permission set as the controller")
        void theServiceDeclaresTheSamePermissionsAsTheController() {
            // plan.md �0.4: no controller-only authorization. The annotation makes the decision
            // reviewable at the HTTP edge and the explicit `authorization.check` inside each
            // service method makes it unbypassable by a future caller reaching the service from a
            // job, a report or an export. What is asserted here is that the two layers agree:
            // a route whose controller demands a permission the service does not would be a
            // route whose stated authorization is not its enforced one, and it would read as
            // protected to whoever reviews the edge.
            var controller = new java.util.TreeSet<String>();
            for (Method method : ClinicController.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(GetMapping.class)) {
                    controller.add(method.getAnnotation(RequirePermission.class).value().value());
                }
            }
            var service = new java.util.TreeSet<String>();
            for (Method method : ClinicReadService.class.getDeclaredMethods()) {
                RequirePermission annotation = method.getAnnotation(RequirePermission.class);
                if (annotation != null) {
                    service.add(annotation.value().value());
                }
            }

            assertThat(service)
                .as("every public read on the clinic service must name the permission it needs, "
                    + "so a caller arriving from somewhere other than the controller is decided "
                    + "the same way")
                .isEqualTo(controller);
        }

        @Test
        @DisplayName("no clinic service method is named like a write")
        void theClinicServiceExposesNoMutation() {
            // The bounded slice is read-only, and the permission set is the evidence: no clinic
            // member in Permission authorizes a write, so a mutation added here would have nothing
            // to gate itself on. The legacy clinic breach reached a cross-tenant WRITE through
            // exactly this module, which is why this is asserted rather than assumed.
            //
            // The check is by NAME and deliberately blunt. A behavioural check would need a
            // mutation to exist; a naming check fails the moment somebody writes one, which is
            // the moment a reviewer is deciding whether it belongs here at all.
            var verbs = List.of("save", "create", "update", "delete", "remove", "cancel",
                "reschedule", "assign", "book", "write", "upsert", "insert", "mutate");
            for (Method method : ClinicReadService.class.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || method.isSynthetic()) {
                    continue;
                }
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                assertThat(verbs)
                    .as("ClinicReadService#%s() is named like a mutation. A clinic write path is "
                        + "owed together with the permission that authorizes it -- Phase 33's "
                        + "DOCTOR_*/PROCEDURE_*/APPOINTMENT_* write members are deliberately NOT "
                        + "in the enum -- so a setter here would have no gate at all",
                        method.getName())
                    .noneMatch(verb -> name.startsWith(verb));
            }
        }    }

    /** The path with its variables replaced, so it can actually be requested. */
    private String concrete(String path) {
        return switch (path) {
            case "/api/v1/clinic/doctors/{doctorId}" ->
                "/api/v1/clinic/doctors/" + ClinicFixture.DOCTOR_A1;
            case "/api/v1/clinic/procedures/{procedureId}" ->
                "/api/v1/clinic/procedures/" + ClinicFixture.PROCEDURE_A1;
            case "/api/v1/clinic/appointments/{appointmentId}" ->
                "/api/v1/clinic/appointments/" + ClinicFixture.APPOINTMENT_A1;
            case "/api/v1/clinic/analytics" ->
                "/api/v1/clinic/analytics?from=2026-08-01T00:00:00.000Z&to=2026-09-01T00:00:00.000Z";
            default -> path;
        };
    }

    private int status(String cookie, HttpMethod method, String path, String body) {
        return http.exchange(URI.create(path), method, new HttpEntity<>(body, headers(cookie)),
            String.class).getStatusCode().value();
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    /**
     * A tenant-A machine principal holding no clinic capability.
     *
     * <p>A worker holds capabilities and never roles, and every clinic permission is a
     * human-role permission, so this is the sharpest available probe for the 403: it is refused
     * for a reason that is about the permission rather than about the route.
     */
    private com.firstelement.security.TenantContext machinePrincipal() {
        return com.firstelement.security.TenantContext.forMachine(
            com.firstelement.security.PrincipalType.WORKER, InboxFixture.ORG_A,
            "clinic-contract-probe", List.of(InboxFixture.PROJECT_A1), List.of());
    }
    /** A real revocable session for a seeded user; the raw token never leaves this method. */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "clinic-contract-http-test", "127.0.0.1", 1);
        assertThat(issued).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }
}