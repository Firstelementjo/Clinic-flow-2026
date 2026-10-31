package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workflow body: parsing, capability checks, and the cycle that loop prevention refuses before a
 * run ever starts.
 */
class WorkflowStateMachineTest {

    private static Map<String, Object> machine(String initial, Object... nodes) {
        return Map.of("initial", initial, "nodes", List.of(nodes));
    }

    /** A node document. {@code next} is legitimately null on the last node, hence the map builder. */
    private static Map<String, Object> node(String id, String actionType, Object next,
                                            Object capabilities) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("id", id);
        node.put("actionType", actionType);
        node.put("next", next);
        node.put("capabilities", capabilities);
        return node;
    }

    private static List<Object> capabilities(String... names) {
        return List.of(names);
    }

    @Test
    @DisplayName("a two-step machine walks from the declared entry point")
    void parsesAndWalks() {
        WorkflowStateMachine parsed = WorkflowStateMachine.parse(
            machine("score", node("score", "SCORE", "route", List.of()),
                node("route", "ROUTE", null, List.of())),
            capabilities("workflow:score", "workflow:route"), 10);

        assertThat(parsed.initial()).isEqualTo("score");
        assertThat(parsed.first().actionType()).isEqualTo("SCORE");
        assertThat(parsed.after("score")).map(WorkflowStateMachine.Node::id).contains("route");
        assertThat(parsed.after("route")).isEmpty();
        assertThat(parsed.ids()).containsExactly("score", "route");
    }

    @Test
    @DisplayName("an initial node that does not exist is refused")
    void unknownInitialNode() {
        assertThatThrownBy(() -> WorkflowStateMachine.parse(
            machine("nowhere", node("score", "SCORE", null, List.of())), List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initial");
    }

    @Test
    @DisplayName("a dangling next pointer is refused")
    void danglingNext() {
        assertThatThrownBy(() -> WorkflowStateMachine.parse(
            machine("score", node("score", "SCORE", "nowhere", List.of())), List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not declare");
    }

    @Test
    @DisplayName("a duplicated node id is refused, because it makes next ambiguous")
    void duplicateNodeId() {
        assertThatThrownBy(() -> WorkflowStateMachine.parse(
            machine("score", node("score", "SCORE", null, List.of()),
                node("score", "ROUTE", null, List.of())), List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }

    @Test
    @DisplayName("a cycle is refused at parse time, before a run can burn its loop budget")
    void cyclesAreRefused() {
        assertThatThrownBy(() -> WorkflowStateMachine.parse(
            machine("a", node("a", "A", "b", List.of()), node("b", "B", "a", List.of())),
            List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");

        assertThatThrownBy(() -> WorkflowStateMachine.parse(
            machine("self", node("self", "A", "self", List.of())), List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("a long chain is checked without recursing")
    void longChainIsIterative() {
        List<Object> nodes = new java.util.ArrayList<>();
        for (int index = 0; index < 5000; index++) {
            nodes.add(node("n" + index, "A", index == 4999 ? null : "n" + (index + 1), List.of()));
        }
        WorkflowStateMachine parsed = WorkflowStateMachine.parse(
            Map.of("initial", "n0", "nodes", nodes), List.of(), 5);

        assertThat(parsed.ids()).hasSize(5000);
        assertThat(parsed.after("n4998")).map(WorkflowStateMachine.Node::id).contains("n4999");
        assertThat(parsed.after("n4999")).isEmpty();
    }

    @Test
    @DisplayName("an empty or non-object document is refused")
    void malformedDocument() {
        assertThatThrownBy(() -> WorkflowStateMachine.parse("not a document", List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> WorkflowStateMachine.parse(Map.of(), List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initial");
        assertThatThrownBy(() -> WorkflowStateMachine.parse(
            Map.of("initial", "a", "nodes", List.of()), List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("an action requiring a capability outside the run's scope is refused, with the gap named")
    void capabilityCheck() {
        WorkflowStateMachine parsed = WorkflowStateMachine.parse(
            machine("send", node("send", "AI_SEND", null, List.of("workflow:ai.send"))),
            capabilities("workflow:score"), 5);

        WorkflowStateMachine.CapabilityCheck check = parsed.checkCapabilities("send",
            List.of("workflow:score"));

        assertThat(check.allowed()).isFalse();
        assertThat(check.missingCapabilities()).containsExactly("workflow:ai.send");
        assertThat(check.capabilityScopeInForce()).isEqualTo("capabilityScope-in-force");
    }

    @Test
    @DisplayName("an action inside the scope is allowed")
    void capabilityGranted() {
        WorkflowStateMachine parsed = WorkflowStateMachine.parse(
            machine("score", node("score", "SCORE", null, List.of("workflow:score"))),
            capabilities("workflow:score"), 5);

        assertThat(parsed.checkCapabilities("score", List.of("workflow:score")).allowed()).isTrue();
    }

    @Test
    @DisplayName("a capabilityScope that is not an array is refused, never read as everything")
    void capabilityScopeMustBeAnArray() {
        assertThatThrownBy(() -> WorkflowStateMachine.parse(
            machine("score", node("score", "SCORE", null, List.of())),
            Map.of("workflow:score", true), 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unrestricted");
    }

    @Test
    @DisplayName("an empty capability scope parses, and every gated action is then refused")
    void emptyCapabilityScopeFailsClosed() {
        WorkflowStateMachine parsed = WorkflowStateMachine.parse(
            machine("send", node("send", "AI_SEND", null, List.of("workflow:ai.send"))), List.of(), 5);

        assertThat(parsed.capabilityScope()).isEmpty();
        assertThat(parsed.checkCapabilities("send", List.of()).allowed()).isFalse();
    }

    @Test
    @DisplayName("the step budget is bounded by the hard maximum")
    void stepBudgetIsBounded() {
        assertThatThrownBy(() -> new WorkflowStateMachine("a", List.of(), Map.of(), 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSteps");
        assertThatThrownBy(() -> new WorkflowStateMachine("a", List.of(), Map.of(),
            WorkflowStateMachine.HARD_MAX_STEPS + 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSteps");
    }

    @Test
    @DisplayName("a node without an action type is refused")
    void nodeValidation() {
        assertThatThrownBy(() -> new WorkflowStateMachine.Node("id", " ", List.of(), null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actionType");
        assertThatThrownBy(() -> new WorkflowStateMachine.Node(" ", "A", List.of(), null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("node id");
    }
}