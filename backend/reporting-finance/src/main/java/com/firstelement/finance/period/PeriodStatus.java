package com.firstelement.finance.period;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The finance period lifecycle.
 *
 * <h2>Why there are four states and not three</h2>
 *
 * <p>{@code OPEN → IN_REVIEW → LOCKED}, plus {@code REOPENED}. The states are the schema's
 * ({@code PeriodStatus}), and the interesting one is {@code REOPENED}: ADR 0007 rule 4 says "Reopen is
 * an explicit, permissioned, audited action", so it needs a state of its own rather than being a
 * {@code LOCKED} row with cleared timestamps. {@code V20261005090000} records why clearing
 * {@code lockedAt} on reopen was rejected: it would make "was this period ever closed?"
 * unanswerable from the row, and the answer only exists in a separate history table.
 *
 * <h2>The schema's own traps, and what this class does about them</h2>
 *
 * <ol>
 *   <li><b>{@code finance_periods_review_check} is a biconditional</b>: {@code status = 'IN_REVIEW'}
 *       iff {@code underReviewAt IS NOT NULL}. So leaving review means clearing the timestamp, and a
 *       caller that forgets will get a CHECK violation rather than a silently odd row. Written
 *       explicitly here.</li>
 *   <li><b>{@code finance_periods_reopened_check} is also a biconditional</b> over three columns, and
 *       {@code finance_periods_reopen_count_state_check} ties {@code reopenCount > 0} to
 *       {@code reopenedAt IS NOT NULL}. So a reopen must increment the counter and set all three
 *       fields together.</li>
 *   <li><b>{@code finance_periods_locked_check} is deliberately ONE-WAY</b>: being LOCKED demands
 *       {@code lockedAt} and {@code lockedByUserId}; the audit columns may outlive the lock. So a
 *       REOPENED period keeps its lock columns and this class never clears them.</li>
 * </ol>
 *
 * <h2>What closes a period and what changes money in it</h2>
 *
 * <p>Locking. And once locked, {@code usage_rollup_snapshots_locked_period_guard} refuses a new
 * snapshot for the period — a trigger rather than a CHECK, because the locked state lives in another
 * row. So the only way a locked period's figures change is an approved
 * {@link FinanceAdjustment}, which is what {@link PeriodLifecycleService#reopen} and
 * {@link AdjustmentService} exist to make possible and auditable.
 */
public enum PeriodStatus {
    OPEN,
    IN_REVIEW,
    LOCKED,
    REOPENED;

    /** Whether a new usage event may be billed into a period in this state. */
    public boolean acceptsNewUsage() {
        return this == OPEN;
    }

    /** Whether a new snapshot may be recorded for a period in this state. */
    public boolean acceptsNewSnapshot() {
        return this == OPEN;
    }

    /** Whether the period's figures are final. */
    public boolean isClosed() {
        return this == LOCKED || this == REOPENED;
    }

    /** The wire value, which is the enum label. */
    public String wireValue() {
        return name();
    }

    public static PeriodStatus fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (PeriodStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException(
            "Unknown PeriodStatus '" + value + "'. Declared values are OPEN,IN_REVIEW,LOCKED,REOPENED.");
    }
}
