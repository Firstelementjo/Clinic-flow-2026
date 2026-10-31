package com.firstelement.automation.domain;

/**
 * One {@code sla_policies} row as the clock evaluator needs it.
 *
 * <h2>Null means "not promised", never zero</h2>
 *
 * <p>The migration says it outright: "Nullable: a policy may promise a resolution budget without
 * promising a next-response budget, and forcing 0 would be a silent 'immediately breached'." So a
 * null budget produces a clock in state {@link SlaClockState#NOT_PROMISED} with no due date, and
 * the difference between "no promise" and "breached" survives all the way to the operator's screen.
 *
 * @param warnBeforeMinutes    warning lead time; may be null
 * @param escalateAfterMinutes when to escalate; may be null
 */
public record SlaPolicySnapshot(String policyId,
                                String key,
                                int priority,
                                SlaClockType clockType,
                                Integer firstResponseMinutes,
                                Integer nextResponseMinutes,
                                Integer resolutionMinutes,
                                String calendarId,
                                Integer warnBeforeMinutes,
                                Integer escalateAfterMinutes) {

    public SlaPolicySnapshot {
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (clockType == null) {
            clockType = SlaClockType.RESOLUTION;
        }
        assertPositive("firstResponseMinutes", firstResponseMinutes);
        assertPositive("nextResponseMinutes", nextResponseMinutes);
        assertPositive("resolutionMinutes", resolutionMinutes);
        // The clock-ordering invariant, re-asserted on the read path. The table CHECK is the
        // authority (the editor and a batch importer are two writers with two code paths), but a
        // read model that trusts a row without saying so is how an inverted budget reaches an
        // operator's screen as a permanent false alarm.
        if (firstResponseMinutes != null && resolutionMinutes != null
            && resolutionMinutes < firstResponseMinutes) {
            throw new IllegalStateException(
                "SLA policy '" + key + "' promises a " + resolutionMinutes + "-minute resolution "
                    + "budget and a " + firstResponseMinutes + "-minute first-response budget; the "
                    + "resolution clock would breach on every healthy ticket");
        }
        if (firstResponseMinutes != null && nextResponseMinutes != null
            && nextResponseMinutes < firstResponseMinutes) {
            throw new IllegalStateException(
                "SLA policy '" + key + "' promises a " + nextResponseMinutes + "-minute "
                    + "next-response budget and a " + firstResponseMinutes + "-minute "
                    + "first-response budget");
        }
    }

    private static void assertPositive(String name, Integer value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0 when present, got " + value);
        }
    }

    /** The budget for one clock, or {@code null} when this policy does not promise it. */
    public Integer budgetFor(SlaClockType clock) {
        return switch (clock) {
            case FIRST_RESPONSE -> firstResponseMinutes;
            case NEXT_RESPONSE -> nextResponseMinutes;
            case RESOLUTION -> resolutionMinutes;
        };
    }
}