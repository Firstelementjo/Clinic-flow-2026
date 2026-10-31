package exportscope.probe;

import com.firstelement.kernel.error.ErrorResponse;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A stand-in for {@code app-bootstrap}'s {@code ErrorHandlingAdvice}, reproduced for one test context.
 *
 * <h2>WHY THIS CLASS IS NOT IN {@code com.firstelement}</h2>
 *
 * <p>Every {@code @RestControllerAdvice} under {@code com.firstelement} is picked up by the whole-context
 * scan {@code ExportsTestApplication} performs, so a catch-all declared inside that namespace would be
 * registered in <em>every</em> suite in this module. A probe that silently changes the advice ladder of six
 * unrelated suites is a probe whose failures cannot be attributed, so this sits outside the scanned
 * namespace and is wired in explicitly by the single suite that needs it — the same arrangement Phase 19's
 * {@code SecurityExceptionAdviceScopeHttpTest} used for its probe controller.
 *
 * <h2>What is reproduced, and what is deliberately not</h2>
 *
 * <p>Two properties, because those two are what the test is about:
 *
 * <ol>
 *   <li><b>The order.</b> {@link Ordered#LOWEST_PRECEDENCE}, declared explicitly exactly as the real floor
 *       declares it.</li>
 *   <li><b>The handling width.</b> A single {@code @ExceptionHandler(Exception.class)} that answers
 *       {@code 500 INTERNAL_ERROR}.</li>
 * </ol>
 *
 * <p>What is <em>not</em> reproduced is every other handler the real floor holds — the framework refusals it
 * maps to {@code 400}/{@code 404}/{@code 405}/{@code 406}/{@code 415}. That is on purpose, and it is what
 * makes the probe trustworthy here: this advice has no handler other than the catch-all, so the
 * <em>only</em> status it is capable of producing is the 500 that is the failure mode under test. Had it
 * carried the real floor's whole menu, a test that saw a 400 could not tell whether the export advice or the
 * floor produced it, which is the entire question.
 *
 * <p>The consequence, stated plainly so nobody reads a behaviour of this probe as production behaviour: in a
 * context carrying this probe, a malformed request body answers 500 rather than the
 * {@code 400 MALFORMED_REQUEST_BODY} {@code ErrorHandlingAdvice} would produce. That is not a defect in the
 * probe. It is the probe working — it demonstrates that the floor is genuinely present, genuinely reachable,
 * and genuinely willing to answer 500, which is what makes the export refusals rendering their own statuses
 * mean something.
 *
 * <h2>Why not the real class</h2>
 *
 * <p>{@code ErrorHandlingAdvice} lives in {@code :backend:app-bootstrap}, which depends on
 * {@code :backend:reporting-exports}. A test-scope dependency the other way would be the module cycle
 * {@code check-architecture.ps1} FE-ARCH-004 forbids, so the assembled-application proof of the real floor's
 * rendered bodies stays where it already is — {@code app-bootstrap}'s own error-mapping suite.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ProbeFloorAdvice {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unhandled(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL_ERROR", "Unexpected server error", Map.of(),
                null, java.time.Instant.now(), null));
    }
}