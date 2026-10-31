package com.firstelement.crm.error;

import org.springframework.http.HttpStatus;
import java.util.Map;

/**
 * Thrown when a clinic exceeds its active plan limits or overrides
 * (e.g., maximum branches, maximum users, storage, or monthly AI quota).
 */
public class PlanLimitExceededException extends CrmDomainException {

    private static final long serialVersionUID = 1L;

    public PlanLimitExceededException(String limitName, int maxAllowed, int currentCount) {
        super(
            HttpStatus.FORBIDDEN,
            "PLAN_LIMIT_EXCEEDED",
            "Subscription limit reached for " + limitName + " (maximum allowed: " + maxAllowed + "). Please upgrade your plan or request a limit increase.",
            Map.of("limit", limitName, "max", maxAllowed, "current", currentCount),
            false
        );
    }
}
