package com.firstelement.automation.domain;

/**
 * What a routing decision is being made for.
 *
 * <p>Ported from {@code packages/routing/src/strategy.ts}'s {@code RoutingRequirements}. A
 * {@code null} department or skill is a <b>wildcard</b>, not a requirement to be null — the legacy
 * rule matcher said so directly: "A null on the rule is a wildcard, not a requirement to be null."
 *
 * <p>{@link Strategy#ROUND_ROBIN} is retained as a declared value and routes least-loaded, which
 * is what the legacy {@code RoundRobinStrategy} did: "it needs a per-project cursor to be genuinely
 * round-robin, and pretending otherwise would be worse than saying so." A cursor is a
 * {@code routing_rules} column this schema does not have, so this module does not invent one; the
 * decision is recorded here and in the phase report rather than being silently "improved".
 */
public record RoutingRequirement(String department,
                                String requiredSkill,
                                Strategy strategy) {

    public enum Strategy {
        LEAST_LOADED,
        ROUND_ROBIN
    }

    public RoutingRequirement {
        if (strategy == null) {
            strategy = Strategy.LEAST_LOADED;
        }
    }

    public static RoutingRequirement leastLoaded(String department, String requiredSkill) {
        return new RoutingRequirement(department, requiredSkill, Strategy.LEAST_LOADED);
    }
}