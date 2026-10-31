package com.firstelement.automation.domain;

import java.util.List;
import java.util.UUID;

/**
 * An escalation rule as an operator edits it. Ported from
 * {@code packages/ai/src/scoring/rules.ts}'s {@code EscalationRuleInput}.
 *
 * @param priority  explicit, and the <b>only</b> ordering term before the id tie-break
 * @param combinator {@code ALL} means every condition must hold; {@code ANY} means one
 * @param persisted whether this rule came from {@code escalation_rules}. A default rule is
 *                 <em>not</em> persisted, and {@code escalations.ruleId} must be null for it: writing
 *                 a synthetic id would point an escalation at a rule row that does not exist
 */
public record EscalationRule(UUID id,
                             String name,
                             boolean enabled,
                             int priority,
                             List<RuleCondition> conditions,
                             Combinator combinator,
                             String department,
                             boolean persisted) {

    public enum Combinator {
        ALL,
        ANY;

        public static Combinator fromWireValue(String value) {
            return "ANY".equals(value) ? ANY : ALL;
        }
    }

    public EscalationRule {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (combinator == null) {
            combinator = Combinator.ALL;
        }
    }

    /** A rule read from {@code escalation_rules}. */
    public EscalationRule(UUID id, String name, boolean enabled, int priority,
                          List<RuleCondition> conditions, Combinator combinator, String department) {
        this(id, name, enabled, priority, conditions, combinator, department, true);
    }

    /** The legacy default shape, for a project that has stored no rules at all. */
    public static EscalationRule defaultRule(String key, String name, int priority,
                                             Combinator combinator, List<RuleCondition> conditions) {
        return new EscalationRule(
            UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            name, true, priority, conditions, combinator, null, false);
    }

    /** Whether this rule's conditions are satisfied by {@code facts}, and which ones. */
    public EscalationRules.Match test(RuleFacts facts) {
        List<RuleCondition> matched = conditions.stream()
            .filter(condition -> condition.test(facts))
            .toList();
        boolean isMatch = combinator == Combinator.ANY
            ? !matched.isEmpty()
            : matched.size() == conditions.size();
        return new EscalationRules.Match(this, matched, isMatch);
    }
}