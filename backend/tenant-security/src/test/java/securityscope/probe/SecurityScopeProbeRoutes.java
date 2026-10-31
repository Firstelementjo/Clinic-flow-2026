package securityscope.probe;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import securityscope.probe.ProbeRefusals.RefusedException;
import securityscope.probe.ProbeRefusals.RowMissingException;

/**
 * The controller this module's advice-scope suite drives.
 *
 * <h2>The package is the point of the fixture</h2>
 *
 * <p>This class is in {@code securityscope.probe} and <b>not</b> in {@code com.firstelement}. That
 * is deliberate and load-bearing. {@code SecurityExceptionAdvice} used to declare
 * {@code @RestControllerAdvice(basePackages = "com.firstelement")}, so a policy denial raised from
 * a controller outside that namespace was not rendered as a non-disclosing {@code 404}: it fell
 * past the advice to the application's {@code Exception} catch-all and came back
 * {@code 500 INTERNAL_ERROR}. Every other controller in the product lives under
 * {@code com.firstelement}, so the trap was latent rather than live -- but a latent trap in the
 * advice that renders the most serious refusals is the kind that fires the day a controller is
 * added outside the convention, and until then it can only be seen by putting a controller
 * somewhere the convention does not cover.
 *
 * <p>The fix widened the advice to every controller. This class is the regression test for it: the
 * suite asserts {@code 404}, and it asserts that the class really is outside the namespace, so the
 * fixture cannot quietly stop covering the defect by being moved back inside it.
 *
 * <p>It is registered explicitly by {@link SecurityScopeProbeConfiguration} rather than by component
 * scan, which is why it does not need to be discoverable from its package. It is named
 * {@code *Routes} rather than {@code *Controller} for the same reason
 * {@code app-bootstrap}'s probe is: {@code export-openapi-paths.ps1} collects every
 * {@code *Controller.java} under {@code backend} to compare against the committed route inventory, and
 * a fixture route must not appear in the contract the Angular client is generated from. The
 * check-openapi-contract stage was run after this class was added and the inventory is unchanged.
 */
@Controller
@ResponseBody
@RequestMapping("/advice-scope-probe")
public class SecurityScopeProbeRoutes {

    /** Another tenant's organization, and a row inside it. Neither ever reaches a response body. */
    private static final UUID FOREIGN_ORGANIZATION =
        UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID FOREIGN_ROW =
        UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /**
     * A cross-tenant read: the caller holds a valid session and asks for a row in an organization
     * they are not a member of. Answers the same body as {@link #missingRow()} and publishes a
     * security event.
     */
    @GetMapping("/cross-tenant")
    public Map<String, Object> crossTenant() {
        throw com.firstelement.security.error.PolicyDeniedException.tenantOutOfScope(
            "organization-out-of-scope", FOREIGN_ORGANIZATION, FOREIGN_ROW);
    }

    /** The same denial raised from a service three layers down, so a nested refusal keeps its status. */
    @GetMapping("/cross-tenant/nested")
    public Map<String, Object> nestedCrossTenant() {
        return new ProbeDenialLayer().load();
    }

    /** A capability denial with no object to leak: {@code 403}, not {@code 404}. */
    @GetMapping("/capability-denied")
    public Map<String, Object> capabilityDenied() {
        throw new AccessDeniedException("Access denied");
    }

    /**
     * A genuinely nonexistent row. Rendered by the floor, with no knowledge that a denial exists,
     * which is what makes it a usable twin.
     */
    @GetMapping("/missing-row")
    public Map<String, Object> missingRow() {
        throw new RowMissingException("Resource not found");
    }

    /** An ordinary bad request. A {@code 400} that publishes no security event. */
    @GetMapping("/refused")
    public Map<String, Object> refused() {
        throw new RefusedException("That employee is not a member of this project");
    }

    /** A fault in this codebase rather than in the request. Must stay a {@code 500}. */
    @GetMapping("/fault")
    public Map<String, Object> fault() {
        throw new IllegalStateException(
            "relation \"case_classification_questions_pkey\" already exists at /var/lib/postgresql");
    }

    /** The route a caller reaches when nothing is wrong, so a green 404 cannot be a broken route. */
    @GetMapping("/reachable")
    public Map<String, Object> reachable() {
        return Map.of("reachable", true);
    }

    /** One layer, so "raised from a service" is a real statement rather than a comment. */
    static final class ProbeDenialLayer {

        Map<String, Object> load() {
            return new ProbeServiceLayer().load();
        }
    }

    /** The layer a repository would be. Nothing here trusts the caller; it just refuses. */
    static final class ProbeServiceLayer {

        Map<String, Object> load() {
            throw com.firstelement.security.error.PolicyDeniedException.projectOutOfScope(
                "project-out-of-scope", FOREIGN_ORGANIZATION, FOREIGN_ROW);
        }
    }
}
