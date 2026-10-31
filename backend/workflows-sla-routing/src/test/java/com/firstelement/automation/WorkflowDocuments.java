package com.firstelement.automation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared document builders for the workflow suites.
 *
 * <p>A workflow body is JSON because an operator authors it, so the suites build the same shape the
 * parser accepts rather than a Java object the parser never sees.
 */
final class WorkflowDocuments {

    private WorkflowDocuments() {
    }

    /** A two-step workflow: score the turn, then route it. */
    static Map<String, Object> scoreThenRoute(String routeCapability) {
        Map<String, Object> score = node("score", "SCORE", "route", List.of("workflow:score"));
        Map<String, Object> route = node("route", "ROUTE", null,
            routeCapability == null ? List.of() : List.of(routeCapability));
        return Map.of("initial", "score", "nodes", List.of(score, route));
    }

    /** A one-step workflow that sends an AI reply, requiring the send capability. */
    static Map<String, Object> sendAiReply(String sendCapability) {
        return Map.of("initial", "send",
            "nodes", List.of(node("send", "AI_SEND", null,
                sendCapability == null ? List.of() : List.of(sendCapability))));
    }

    /** A document with a cycle, refused at parse time. */
    static Map<String, Object> cyclic() {
        return Map.of("initial", "a",
            "nodes", List.of(node("a", "A", "b", List.of()), node("b", "B", "a", List.of())));
    }

    /** A document whose node requires a capability no run will hold. */
    static Map<String, Object> requiresUngrantedCapability(String capability) {
        return Map.of("initial", "send",
            "nodes", List.of(node("send", "AI_SEND", null, List.of(capability))));
    }

    private static Map<String, Object> node(String id, String actionType, String next,
                                            List<String> capabilities) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("actionType", actionType);
        node.put("next", next);
        node.put("capabilities", capabilities);
        return node;
    }
}