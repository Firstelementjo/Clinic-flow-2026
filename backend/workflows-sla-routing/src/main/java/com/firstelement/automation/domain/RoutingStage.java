package com.firstelement.automation.domain;

/**
 * The filters the routing pipeline runs, in order.
 *
 * <p>The names are the legacy {@code RoutingStage} union verbatim, because they are what an
 * operator reads when a ticket cannot be placed: "'No one is available' and 'everyone is at
 * capacity' are different problems for an ops manager, and a routing engine that cannot tell them
 * apart is not much use at 9pm."
 */
public enum RoutingStage {

    /** No employee is a member of this project at all. */
    MEMBERSHIP("No employees are assigned to this project"),

    /** A rule or escalation named a department nobody is in. */
    DEPARTMENT("No employee in the required department"),

    /** A rule required a skill nobody declares. */
    SKILL("No employee has the required skill"),

    /** Everybody is offline, on a break, at lunch, in training or in a meeting. */
    STATUS("Nobody is available right now"),

    /** Everybody who is at the desk is outside their working hours. */
    WORKING_HOURS("Nobody is inside their working hours"),

    /** Everybody eligible is at their conversation limit. */
    WORKLOAD("Everyone eligible is at their conversation limit"),

    /** An eligible employee was found. Never a blocking stage. */
    NONE("No eligible employee found");

    private final String explanation;

    RoutingStage(String explanation) {
        this.explanation = explanation;
    }

    /** The sentence an operator reads. Legacy {@code describeBlock}, unchanged. */
    public String explanation() {
        return explanation;
    }
}