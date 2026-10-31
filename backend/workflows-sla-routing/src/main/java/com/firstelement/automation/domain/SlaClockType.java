package com.firstelement.automation.domain;

/**
 * The three SLA clocks. The enum values are exactly
 * {@code public."SlaClockType"}'s values ({@code FIRST_RESPONSE}, {@code NEXT_RESPONSE},
 * {@code RESOLUTION}), so the stored spelling and the Java spelling are one vocabulary.
 *
 * <p>Why three and not one duration plus a repeat count is recorded in
 * {@code V20261005030000__sla_calendars.sql}: "A clinic may answer within 15 minutes and still take
 * a day to resolve. One duration plus a repeat count makes the second clock inexpressible." Two
 * nullable integer columns plus a resolution column is the shape the operator edits, and this enum
 * is the vocabulary the scheduler reads.
 */
public enum SlaClockType {

    FIRST_RESPONSE,
    NEXT_RESPONSE,
    RESOLUTION;

    public static SlaClockType fromWireValue(String value) {
        if (value == null) {
            return null;
        }
        for (SlaClockType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown SlaClockType: " + value);
    }
}