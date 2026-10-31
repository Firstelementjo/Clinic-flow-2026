package com.firstelement.automation.domain;

import java.util.List;
import java.util.UUID;

/**
 * One possible assignee, as the routing pipeline sees them.
 *
 * <h2>Ported from {@code packages/routing/src/strategy.ts} verbatim</h2>
 *
 * <p>Including the two decisions that look like omissions and are not:
 *
 * <ul>
 *   <li>{@code BUSY} stays eligible. The legacy comment: "BUSY is a person mid-task, not one who
 *       has gone home: they stay eligible if they have headroom, and the workload filter decides."
 *   <li>{@code BREAK}, {@code MEAL}, {@code TRAINING} and {@code MEETING} are all "not at the
 *       desk" — "the distinction between them is for reporting, not for routing."</li>
 * </ul>
 *
 * <p>{@code fullName} is carried because the legacy engine returned it in its route result, and an
 * operator asking "why did this go to Layla" needs the name, not only the id.
 *
 * @param skills the employee's declared skills and levels; level breaks ties on the required skill
 */
public record RoutingCandidate(UUID employeeId,
                               UUID userId,
                               String fullName,
                               String department,
                               String availabilityStatus,
                               int activeConversationCount,
                               int maxConcurrentConversations,
                               List<Skill> skills,
                               List<AvailabilityWindow> windows) {

    public RoutingCandidate {
        if (employeeId == null) {
            throw new IllegalArgumentException("employeeId is required");
        }
        skills = skills == null ? List.of() : List.copyOf(skills);
        windows = windows == null ? List.of() : List.copyOf(windows);
        if (maxConcurrentConversations < 0) {
            throw new IllegalArgumentException("maxConcurrentConversations must not be negative");
        }
        if (activeConversationCount < 0) {
            throw new IllegalArgumentException("activeConversationCount must not be negative");
        }
    }

    /** One declared skill and its level. */
    public record Skill(String skill, int level) {
    }

    /** Whether the employee is at the desk. Mirrors {@code EmployeeRepository.isRoutable}. */
    public boolean atDesk() {
        return "ONLINE".equals(availabilityStatus) || "BUSY".equals(availabilityStatus);
    }

    /** The legacy capacity test: at or above the limit counts as full. */
    public boolean atCapacity() {
        return activeConversationCount >= maxConcurrentConversations;
    }

    /** The declared level of {@code name}, or 0 when the employee does not have it. */
    public int skillLevel(String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        return skills.stream()
            .filter(entry -> name.equals(entry.skill()))
            .mapToInt(Skill::level)
            .max()
            .orElse(0);
    }

    public boolean hasSkill(String name) {
        return name != null && !name.isBlank()
            && skills.stream().anyMatch(entry -> name.equals(entry.skill()));
    }
}