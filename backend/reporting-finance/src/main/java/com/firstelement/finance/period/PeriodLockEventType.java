package com.firstelement.finance.period;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The append-only history of a period's lifecycle: {@code finance_period_lock_events}.
 *
 * <h2>Why a history and not just the state column</h2>
 *
 * <p>Because they answer different questions and only the history answers the one an auditor asks.
 * {@code finance_periods.status} answers "is it locked now"; {@code finance_period_lock_events} answers
 * "was it ever locked, by whom, when, and why was it reopened". The migration says exactly this and
 * gives the reason: the row state answers "is it locked now", the history answers "was it ever locked",
 * and only the second is answerable to an auditor. A reopened period keeps its lock columns precisely so
 * both questions survive on one row.
 *
 * <h2>The seven event types, and which of them must carry a note</h2>
 *
 * <p>{@code finance_period_lock_events_note_check} requires a non-blank note on {@code LOCKED},
 * {@code REOPENED} and {@code UNLOCKED}, because those are the actions that change a period's
 * disposition. A review flag and an approved adjustment say what they are in their own columns, so their
 * note is optional.
 *
 * <p>{@link #UNLOCKED} is present in the schema's CHECK but this module does not emit it: unlocking is
 * expressed as {@link #REOPENED}, which carries the reason and the counter increment. Emitting an
 * {@code UNLOCKED} row as well would put two events on one state change and make the count of reopenings
 * ambiguous.
 */
public enum PeriodLockEventType {

    /** A period entered review. Optional note: the timestamp says when. */
    UNDER_REVIEW("UNDER_REVIEW"),

    /** A period was approved and locked. Note required. */
    LOCKED("LOCKED"),

    /**
     * A period was reopened for an approved correction. Note required.
     *
     * <p>This is the event ADR 0007 rule 4's "reopen is an explicit, permissioned, audited action"
     * refers to. It is emitted on the transition into {@code REOPENED}, which is also when
     * {@code finance_periods.reopenCount} increments.
     */
    REOPENED("REOPENED"),

    /** Declared by the schema; not emitted by this module. See the class note. */
    UNLOCKED("UNLOCKED"),

    /** An adjustment was approved against this period. */
    ADJUSTMENT_APPROVED("ADJUSTMENT_APPROVED"),

    /** An adjustment was rejected. */
    ADJUSTMENT_REJECTED("ADJUSTMENT_REJECTED"),

    /**
     * A snapshot was recomputed inside the period.
     *
     * <p>Only legal while the period is OPEN or, after a reopen, REOPENED. On a LOCKED period the
     * database refuses the snapshot itself, so this event can never pair with a locked one.
     */
    SNAPSHOT_REFRESHED("SNAPSHOT_REFRESHED");

    private final String label;

    PeriodLockEventType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether the schema requires a non-blank note on this event type. */
    public boolean requiresNote() {
        return this == LOCKED || this == REOPENED || this == UNLOCKED;
    }

    /** One history row. */
    public record PeriodLockEvent(UUID id,
                                  UUID organizationId,
                                  UUID projectId,
                                  UUID periodId,
                                  String eventType,
                                  UUID actorUserId,
                                  String actorPrincipal,
                                  String note,
                                  LocalDateTime eventAt) {
    }
}
