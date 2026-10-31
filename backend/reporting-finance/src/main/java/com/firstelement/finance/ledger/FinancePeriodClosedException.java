package com.firstelement.finance.ledger;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Usage arrived for a finance period that is no longer open, and the deployment is configured to
 * refuse it rather than record it unattached.
 *
 * <h2>Why this is a distinct exception and not a validation failure</h2>
 *
 * <p>Nothing about the event is malformed. The quantity is sane, the source names a real row, the
 * dimension is declared. What failed is a <em>policy</em>: ADR 0007 rule 4 says a closed period
 * changes only through an approved, audited correction, and the deployment chose the strict reading.
 *
 * <p>Collapsing that into {@code IllegalArgumentException} would make it indistinguishable from a
 * programmer error at the call site, and the right response to each is different: a malformed event is
 * a bug to fix in code, a late arrival is an operational event to route to a correction. So the
 * exception carries who, which period, what state, and which day — everything the correction workflow
 * needs to start without re-deriving anything.
 */
public class FinancePeriodClosedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient UUID organizationId;
    private final transient UUID periodId;
    private final transient String periodStatus;
    private final transient LocalDate usageDate;

    public FinancePeriodClosedException(UUID organizationId, UUID periodId, String periodStatus,
                                        LocalDate usageDate) {
        super("Finance period " + periodId + " of organization " + organizationId + " is "
            + periodStatus + ", so usage on " + usageDate + " was refused. ADR 0007 rule 4: a closed "
            + "period changes only through an approved, audited correction linked to source events. "
            + "Either reopen the period (permissioned and audited) or raise a finance adjustment.");
        this.organizationId = organizationId;
        this.periodId = periodId;
        this.periodStatus = periodStatus;
        this.usageDate = usageDate;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID periodId() {
        return periodId;
    }

    /** The status that refused the write: {@code LOCKED}, {@code IN_REVIEW} or {@code REOPENED}. */
    public String periodStatus() {
        return periodStatus;
    }

    /** The date the usage occurred. */
    public LocalDate usageDate() {
        return usageDate;
    }
}
