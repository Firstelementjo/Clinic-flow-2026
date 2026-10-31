package securityscope.probe;

/**
 * The two non-security refusals this module's HTTP suite needs a handler for, declared here so
 * that neither the security advice nor the floor has to claim a type it does not own in
 * production.
 *
 * <p>{@link RowMissingException} stands in for a domain's "the row is not there". It is the twin a
 * cross-tenant denial has to be indistinguishable from, so it must be raised by the ordinary
 * missing-row path and rendered by an advice that has nothing to do with security.
 *
 * <p>{@link RefusedException} stands in for the domain validation family: an ordinary bad
 * request. It is here for the negative that matters most when an advice is widened -- an advice
 * that starts claiming more must not start publishing security events for it.
 *
 * <p>Neither type is a {@code CrmDomainException} and neither extends anything from Spring
 * Security, because this module cannot depend on {@code :backend:crm-core} (that module depends on
 * this one). The assembled-application proof of the same behaviour is
 * {@code app-bootstrap}'s {@code ErrorMappingHttpIntegrationTest}; this suite is the proof of the
 * <em>scope</em> change, which no other suite can reach from here.
 */
public final class ProbeRefusals {

    private ProbeRefusals() {
    }

    /** A row that genuinely does not exist. Carries no tenant, project or role, as no 404 body may. */
    public static class RowMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RowMissingException(String message) {
            super(message);
        }
    }

    /** An ordinary client mistake. Not a security event, and must never become one. */
    public static class RefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RefusedException(String message) {
            super(message);
        }
    }
}
