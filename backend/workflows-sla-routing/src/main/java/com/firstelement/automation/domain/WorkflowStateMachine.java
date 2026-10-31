package com.firstelement.automation.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The versioned workflow body: {@code workflow_versions.stateMachine}, parsed.
 *
 * <h2>Shape</h2>
 *
 * <p>JSON, deliberately. A workflow is authored by an operator and must change without a deploy, and
 * an operator can only be asked to edit a graph they can see. The body is
 * {@code {"initial":"<node>","nodes":[{"id":..,"actionType":..,"capabilities":[..],"next":..}]}}:
 * a small, explicit DAG-shaped document with one entry node per action and one named next node,
 * which is enough to express escalation/routing/render pipelines and is impossible to write a
 * cycle into by accident.
 *
 * <h2>Loop prevention has three layers, on purpose</h2>
 *
 * <ol>
 *   <li>{@link Plan#cycle()} — a parsed body whose next-pointers form a cycle is <b>refused at
 *       parse time</b>. The schema's {@code loopDepth <= maxLoopDepth} CHECK cannot catch a cycle
 *       inside a jsonb document, so the parser is where it is caught.</li>
 *   <li>{@code workflow_runs.(organizationId, triggerInstanceId)} UNIQUE — one run per trigger
 *       instance per tenant, which is what stops a re-fired trigger producing a second run
 *       ({@code workflow-sla-statements.sql} §4, {@code ON CONFLICT DO NOTHING}).</li>
 *   <li>{@code workflow_runs.loopDepth/maxLoopDepth} — recorded per execution and checked before
 *       the next run is started.</li>
 * </ol>
 *
 * <p>Layer 1 alone is not enough (a document can be a DAG and still be re-entered by a new trigger)
 * and layer 2 alone is not enough (a cycle inside one run still burns the budget), so all three
 * exist and each is tested.
 */
public record WorkflowStateMachine(String initial,
                                  List<Node> nodes,
                                  Map<String, String> capabilityScope,
                                  int maxSteps) {

    /** Upper bound on the actions one run may attempt. A safety net under the loop budget. */
    public static final int HARD_MAX_STEPS = 100;

    public WorkflowStateMachine {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        capabilityScope = capabilityScope == null ? Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(capabilityScope));
        if (maxSteps < 1 || maxSteps > HARD_MAX_STEPS) {
            throw new IllegalArgumentException(
                "maxSteps must be 1.." + HARD_MAX_STEPS + ", got " + maxSteps);
        }
    }

    /** One action node. */
    public record Node(String id,
                       String actionType,
                       List<String> requiredCapabilities,
                       String next,
                       Map<String, Object> config) {

        public Node {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("node id is required");
            }
            if (actionType == null || actionType.isBlank()) {
                throw new IllegalArgumentException("node '" + id + "' has no actionType");
            }
            requiredCapabilities = requiredCapabilities == null ? List.of()
                : List.copyOf(requiredCapabilities);
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    /**
     * Parses the jsonb body.
     *
     * @throws IllegalArgumentException when the document is not a machine this engine can run —
     *         an unknown initial node, a dangling {@code next}, a duplicate node id, or a cycle
     */
    @SuppressWarnings("unchecked")
    public static WorkflowStateMachine parse(Object stateMachineJson, Object capabilityScopeJson,
                                             int maxSteps) {
        if (!(stateMachineJson instanceof Map<?, ?> document)) {
            throw new IllegalArgumentException(
                "stateMachine must be a JSON object with 'initial' and 'nodes'");
        }
        Object initial = document.get("initial");
        if (!(initial instanceof String initialNode) || initialNode.isBlank()) {
            throw new IllegalArgumentException(
                "stateMachine.initial must name the entry node; a workflow with no declared entry "
                    + "point cannot be executed");
        }
        Object rawNodes = document.get("nodes");
        if (!(rawNodes instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("stateMachine.nodes must be a non-empty JSON array");
        }

        Map<String, Node> byId = new LinkedHashMap<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("stateMachine.nodes[] entries must be objects");
            }
            Object id = raw.get("id");
            Object actionType = raw.get("actionType");
            Object next = raw.get("next");
            List<String> capabilities = new java.util.ArrayList<>();
            if (raw.get("capabilities") instanceof List<?> required) {
                for (Object value : required) {
                    if (value != null) {
                        capabilities.add(String.valueOf(value));
                    }
                }
            }
            Map<String, Object> config = raw.get("config") instanceof Map<?, ?> cfg
                ? (Map<String, Object>) cfg : Map.of();
            Node node = new Node(String.valueOf(id), String.valueOf(actionType), capabilities,
                next == null ? null : String.valueOf(next), config);
            if (byId.put(node.id(), node) != null) {
                throw new IllegalArgumentException("stateMachine declares node '" + node.id()
                    + "' twice; a duplicated node id makes 'next' ambiguous");
            }
        }

        if (!byId.containsKey(initialNode)) {
            throw new IllegalArgumentException("stateMachine.initial names node '" + initialNode
                + "', which the document does not declare");
        }
        for (Node node : byId.values()) {
            if (node.next() != null && !byId.containsKey(node.next())) {
                throw new IllegalArgumentException("node '" + node.id() + "' points at '" + node.next()
                    + "', which the document does not declare");
            }
        }

        Map<String, String> scope = new LinkedHashMap<>();
        if (capabilityScopeJson instanceof List<?> capabilityList) {
            for (Object value : capabilityList) {
                if (value != null) {
                    scope.put(String.valueOf(value), String.valueOf(value));
                }
            }
        } else if (capabilityScopeJson != null) {
            throw new IllegalArgumentException(
                "capabilityScope must be a JSON array of capability names. An object or a null here "
                    + "would mean a capability set that cannot be diffed against another version, "
                    + "and an unrestricted set is exactly what plan.md forbids");
        }

        WorkflowStateMachine machine =
            new WorkflowStateMachine(initialNode, List.copyOf(byId.values()), scope, maxSteps);
        machine.requireAcyclic();
        return machine;
    }

    /**
     * Refuses a cycle.
     *
     * <p>Iterative, not recursive: a hand-authored document can chain a thousand nodes, and a
     * recursive check on one is a {@code StackOverflowError} rather than a diagnosis.
     */
    public void requireAcyclic() {
        Map<String, Integer> marks = new LinkedHashMap<>();
        for (String startId : ids()) {
            if (marks.getOrDefault(startId, 0) == 2) {
                continue;
            }
            java.util.Deque<String> path = new java.util.ArrayDeque<>();
            String cursor = startId;
            while (cursor != null) {
                Integer mark = marks.get(cursor);
                if (mark != null && mark == 1) {
                    throw new IllegalArgumentException(
                        "stateMachine contains a cycle through node '" + cursor
                            + "'. A workflow that can revisit a node is a loop the executor would "
                            + "keep re-entering, and the only way to stop it is the step budget -- "
                            + "which is a symptom, not a control");
                }
                if (mark != null && mark == 2) {
                    break;
                }
                marks.put(cursor, 1);
                path.push(cursor);
                final String visited = cursor;
                Node node = node(visited).orElseThrow(
                    () -> new IllegalArgumentException("node '" + visited + "' vanished mid-walk"));
                cursor = node.next();
            }
            for (String visited : path) {
                marks.put(visited, 2);
            }
        }
    }

    public java.util.Set<String> ids() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (Node node : nodes) {
            ids.add(node.id());
        }
        return ids;
    }

    public Optional<Node> node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst();
    }

    /** The entry node. */
    public Node first() {
        return node(initial).orElseThrow(
            () -> new IllegalStateException("validated machine has no initial node: " + initial));
    }

    /** The next node after {@code nodeId}, or empty when the run finishes here. */
    public Optional<Node> after(String nodeId) {
        Node node = node(nodeId).orElseThrow(
            () -> new IllegalArgumentException("unknown node '" + nodeId + "'"));
        return node.next() == null ? Optional.empty() : node(node.next());
    }

    /** Whether {@code nodeId} may run under {@code granted}, and why not when it may not. */
    public CapabilityCheck checkCapabilities(String nodeId, java.util.Collection<String> granted) {
        Node node = node(nodeId).orElseThrow(
            () -> new IllegalArgumentException("unknown node '" + nodeId + "'"));
        List<String> missing = node.requiredCapabilities().stream()
            .filter(required -> !granted.contains(required))
            .toList();
        return missing.isEmpty()
            ? CapabilityCheck.granted()
            : new CapabilityCheck(false, node.actionType(), missing, "capabilityScope-in-force");
    }

    /**
     * Whether an action may run under the capabilities the run was started with.
     *
     * @param nodeId  the node being attempted
     * @param granted the run's copied {@code capabilityScope} — the version's, snapshotted onto
     *                the run so a later widening cannot retroactively enable this action
     */
    public record CapabilityCheck(boolean allowed,
                                  String actionType,
                                  List<String> missingCapabilities,
                                  String capabilityScopeInForce) {

        public CapabilityCheck {
            missingCapabilities = missingCapabilities == null ? List.of()
                : List.copyOf(missingCapabilities);
        }

        static CapabilityCheck granted() {
            return new CapabilityCheck(true, null, List.of(), "");
        }
    }
}