package com.firstelement.kernel.contract;

import java.util.List;

/**
 * The record of what each asynchronous route was <b>observed</b> to return, and the evidence for it.
 *
 * <h2>Why this table exists, and why it is not a list of exceptions</h2>
 *
 * <p>ADR 0016 rule 1 is "observe, then declare", and rule 3 is "every declared code must be
 * reachable". Both together mean the document may not be edited to match whatever a controller
 * happens to return: the observation comes first, and it is made over HTTP by a suite that lives
 * beside the route.
 *
 * <p>This table is that record, and it is deliberately small. The first measurement found 52
 * POST/PUT/PATCH operations publishing only {@code 200}, and the instinct is to declare {@code 202}
 * on all of them. Executing each one says otherwise: almost every one of those routes is a
 * <b>synchronous</b> mutation that completes inside the request. {@code PATCH /conversations/{id}}
 * writes and returns the written row. {@code POST /finance/periods/{id}/lock} locks and returns
 * {@code LOCKED}. {@code POST /finance/reconciliation/run} returns the finished
 * {@code ReconciliationResult}. Declaring {@code 202} on any of those would be a different lie in the
 * other direction — it would tell a client the work had not happened when it had.
 *
 * <p>Three routes accept work that is genuinely outstanding when the response is written, and those
 * three are the whole table.
 *
 * <h2>Why the evidence travels with the entry</h2>
 *
 * <p>{@link Observation#evidence()} names the suite that executed the route and the state it read
 * afterwards. An entry whose evidence is a sentence rather than a class name is an entry somebody
 * reasoned its way into, and {@link #declaredBy(String)} lets the contract suite refuse it: ADR 0016
 * rule 4 says the correct action where the real code is not established by a test is to write the
 * test, not to pick a plausible value.
 */
public final class ObservedRouteStatuses {

    private ObservedRouteStatuses() {
    }

    /**
     * One observed route.
     *
     * @param method   the HTTP method, upper case, as the OpenAPI {@code paths} map keys it
     * @param path     the templated path exactly as springdoc publishes it
     * @param status   the status the route was observed to return
     * @param evidence the suite that executed it, and the state it read afterwards
     */
    public record Observation(String method, String path, int status, String evidence) {

        public Observation {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("an observation must name its HTTP method");
            }
            if (path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException(
                    "an observation must name its templated path, and it must start with '/': " + path);
            }
            if (status < 200 || status > 299) {
                throw new IllegalArgumentException(
                    "ADR-0016 is about SUCCESS codes; " + method + " " + path + " observed " + status
                        + ", which is not one. A refusal belongs in RefusalRules, not here.");
            }
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException(
                    "ADR-0016 rule 4: " + method + " " + path + " has no evidence that it was "
                        + "executed. Write the test; do not pick a plausible value.");
            }
        }

        /** {@code "METHOD /path"} — the form every failure message uses. */
        public String key() {
            return method + " " + path;
        }
    }

    /**
     * Every route whose work is <b>outstanding</b> when the response is written.
     *
     * <p>Read "outstanding" strictly: the response carries a handle or an acknowledgement, and the
     * thing the caller asked for does not exist yet. That is the test a client applies when it
     * decides whether to poll.
     */
    public static final List<Observation> ACCEPTED_OPERATIONS = List.of(
        new Observation("POST", "/api/v1/exports", 202,
            "ReportAuthoringIntegrationTest#authorsEverythingAndCompletesAnExport posts a real "
                + "export, asserts 202, then reads the job row and finds status QUEUED with a null "
                + "storageKey, a null checksum and startedAt null: the artefact does not exist."),
        new Observation("POST", "/api/v1/exports/{exportId}/retry", 202,
            "AsyncStatusHttpIntegrationTest#aRetryIsAcceptedNotCompleted retries a FAILED job over "
                + "HTTP, asserts 202, then reads the row back and finds status QUEUED with no "
                + "storageKey: the retry has been accepted and nothing has been generated."));

    /**
     * One operation whose outcome — and therefore whose status — depends on what the service
     * decided.
     *
     * <p>Only the replay route, and only because ADR-0016 rule 3 had to be read rather than applied
     * mechanically. The rule forbids "declaring both {@code 200} and {@code 202} on an operation
     * <em>that only ever returns one</em>". This operation returns both, and each was observed over
     * a real socket: an applied replay leaves the outbox row {@code PENDING} with no worker holding
     * it, which is outstanding work; a refused replay wrote nothing and completed on the request
     * thread, which is finished work. Declaring either code alone would have been a lie — and
     * answering a refusal with {@code 202} is the more dangerous half of that lie, because an
     * operator would poll a job that will never run.
     *
     * <p>So two codes here are not the hedging rule 3 forbids; they are rule 3 satisfied. The
     * customiser requires the document to carry exactly this set, and
     * {@code ReplayAsyncStatusHttpIntegrationTest} asserts both statuses over HTTP.
     */
    public record SplitOutcome(String method, String path, List<Observation> outcomes) {

        public SplitOutcome {
            if (method == null || method.isBlank() || path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException(
                    "a split outcome must name its method and its templated path");
            }
            if (outcomes == null || outcomes.size() < 2) {
                throw new IllegalArgumentException(
                    "a split exists because an operation returns more than one success code; "
                        + method + " " + path + " lists "
                        + (outcomes == null ? 0 : outcomes.size()) + ". A single code is not a split "
                        + "and belongs in ACCEPTED_OPERATIONS.");
            }
            outcomes = List.copyOf(outcomes);
            for (Observation outcome : outcomes) {
                if (!outcome.method().equals(method) || !outcome.path().equals(path)) {
                    throw new IllegalArgumentException(
                        "every outcome in a split must name its own operation; found " + outcome.key()
                            + " inside " + method + " " + path);
                }
            }
        }

        public String key() {
            return method + " " + path;
        }
    }

    /** The one operation whose success code depends on the service's decision. */
    public static final List<SplitOutcome> SPLIT_OUTCOME_OPERATIONS = List.of(
        new SplitOutcome("POST", "/api/v1/operations/jobs/{outboxEventId}/replay", List.of(
            new Observation("POST", "/api/v1/operations/jobs/{outboxEventId}/replay", 202,
                "ReplayAsyncStatusHttpIntegrationTest#aReplayIsAcceptedNotCompleted replays a DEAD "
                    + "outbox event over HTTP, asserts 202, then reads outbox_events and finds state "
                    + "PENDING with the attempt count unchanged: the event is claimable again and no "
                    + "worker has delivered it."),
            new Observation("POST", "/api/v1/operations/jobs/{outboxEventId}/replay", 200,
                "ReplayAsyncStatusHttpIntegrationTest#aRefusedReplayIsNotAcceptedWork replays an "
                    + "event on a queue that is not replay-safe, asserts 200, and finds the row "
                    + "still DEAD with applied=false: the refusal completed on the request thread."))));

    /**
     * Routes that deliberately acknowledge rather than accept.
     *
     * <p>Published so the reason a webhook is not in {@link #ACCEPTED_OPERATIONS} is a fact in the
     * document rather than an absence a reader has to infer. ADR 0016 rule 2 names this case
     * explicitly: a webhook acknowledgement {@code 200} is correct, because the route completed its
     * purpose — durably queueing the event — and because Meta and Telegram disable a webhook that
     * answers non-2xx.
     */
    public static final List<Observation> ACKNOWLEDGING_OPERATIONS = List.of(
        new Observation("POST", "/webhooks/meta/{connectionId}", 200,
            "ChannelWebhookController#handle returns 200 on a verified request by design; the "
                + "durable queue, not the HTTP response, carries the work."),
        new Observation("POST", "/webhooks/mock/{connectionId}", 200,
            "ChannelWebhookController#handle returns 200 on a verified request by design."),
        new Observation("POST", "/webhooks/telegram/{connectionId}", 200,
            "ChannelWebhookController#handle returns 200 on a verified request by design."));

    /** Every operation whose observed success code this module publishes, in one list. */
    public static List<Observation> governed() {
        List<Observation> all = new java.util.ArrayList<>(ACCEPTED_OPERATIONS);
        all.addAll(ACKNOWLEDGING_OPERATIONS);
        SPLIT_OUTCOME_OPERATIONS.forEach(split -> all.addAll(split.outcomes()));
        return List.copyOf(all);
    }

    /** Every operation this table governs, keyed {@code "METHOD /path"}, one entry per operation. */
    public static java.util.Map<String, List<Observation>> governedByOperation() {
        java.util.Map<String, List<Observation>> byOperation = new java.util.LinkedHashMap<>();
        for (Observation observation : governed()) {
            byOperation.computeIfAbsent(observation.key(), k -> new java.util.ArrayList<>())
                .add(observation);
        }
        byOperation.replaceAll((key, list) -> List.copyOf(list));
        return java.util.Collections.unmodifiableMap(byOperation);
    }

    /**
     * The suite that observed an entry, extracted from its evidence.
     *
     * <p>Used by the contract suite to fail an entry that names no executable suite. Rule 4 in
     * operational form: a status nobody executed is a guess, and a guess in the contract is a
     * promise nobody made good.
     */
    public static String declaredBy(Observation observation) {
        int marker = observation.evidence().indexOf('#');
        if (marker < 0) {
            return "";
        }
        String head = observation.evidence().substring(0, marker);
        String tail = head.substring(head.lastIndexOf(' ') + 1);
        return tail;
    }
}


