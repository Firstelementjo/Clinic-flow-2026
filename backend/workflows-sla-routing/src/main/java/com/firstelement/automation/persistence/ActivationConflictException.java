package com.firstelement.automation.persistence;

/**
 * Raised when a workflow activation cannot complete and the transaction must roll back.
 *
 * <p>Never swallowed and never retried automatically. {@code workflow-sla-statements.sql} §1 says
 * what each row count means and why continuing is wrong: "0 means it was already active, or it is
 * ROLLED_BACK / ARCHIVED: ROLLBACK, never continue." A half-applied activation leaves a definition
 * with no live body, which is a workflow that silently stops running — the exact failure a versioned
 * activation exists to make impossible.
 */
public class ActivationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int retiredRows;
    private final int activatedRows;
    private final int pointerRows;

    public ActivationConflictException(String message, int retiredRows, int activatedRows,
                                       int pointerRows) {
        super(message);
        this.retiredRows = retiredRows;
        this.activatedRows = activatedRows;
        this.pointerRows = pointerRows;
    }

    /** Step 1's affected rows. 0 is legitimate on a first activation; &gt;1 means a concurrent writer. */
    public int retiredRows() {
        return retiredRows;
    }

    /** Step 2's affected rows. Must be exactly 1. */
    public int activatedRows() {
        return activatedRows;
    }

    /** Step 3's affected rows. Must be exactly 1. */
    public int pointerRows() {
        return pointerRows;
    }

}
