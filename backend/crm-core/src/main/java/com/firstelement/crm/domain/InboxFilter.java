package com.firstelement.crm.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The inbox's saved views. The wire value is the legacy kebab-case string so an existing
 * client's saved filter keeps working unchanged.
 *
 * <p>These are named filters rather than ad-hoc query params so the same vocabulary exists
 * in the UI, the API and the analytics that count them — see
 * {@code apps/api/src/conversations/inbox-filter.ts} and its port
 * {@code com.firstelement.crm.query.InboxFilterDefinition}.
 *
 * <h2>Why this enum is the single source of the contract</h2>
 *
 * <p>The {@code filter} query parameter is declared as THIS TYPE in the controller, so springdoc
 * publishes its values as an {@code enum} in the OpenAPI document rather than as a bare
 * {@code type: string}. That matters because an unconstrained string parameter in a public contract
 * is not a style preference: it invites a client to send a value the server cannot answer, and
 * {@code plan.md} requires that a request the server cannot answer be a <em>refusal</em>, never a
 * 500. Deriving the documented values from the type means there is exactly one list, so the
 * contract cannot drift away from what the server accepts.
 *
 * <p>It is also why {@code /inbox/counts} must carry a key for every member: a segmented control
 * whose tabs are the values below renders a badge per tab, and a missing key is a permanent zero
 * that looks like a broken product rather than a missing number.
 *
 * <h2>The declaration order is the tab order</h2>
 *
 * <p>{@link InboxQueryService#savedViewCounts} returns a {@code LinkedHashMap} in declaration
 * order, so this ordering is the hint a client uses to lay the segmented control out. The work
 * queue is first because it is the view that has to be findable: the inbox's primary screen could
 * not show an unassigned conversation at all until {@link #UNASSIGNED} existed.
 *
 * <h2>What each view means, exactly</h2>
 *
 * <table>
 *   <caption>the vocabulary</caption>
 *   <tr><th>wire value</th><th>meaning</th></tr>
 *   <tr><td>{@code unassigned}</td>
 *       <td>OPEN or PENDING with <b>no owner at all</b>, and <b>no involvement test whatsoever</b>.
 *           The work queue.</td></tr>
 *   <tr><td>{@code all-open}</td>
 *       <td>OPEN or PENDING, anyone's. A supervisor's whole load.</td></tr>
 *   <tr><td>{@code assigned-to-others}</td>
 *       <td>OPEN or PENDING, assigned to an employee who is <b>not</b> the caller.</td></tr>
 *   <tr><td>{@code assigned-to-me}</td>
 *       <td>OPEN or PENDING and the caller is involved — assigned to them, <b>or</b> the caller
 *           was the last human replier, which keeps a released thread visible to whoever
 *           answered it.</td></tr>
 *   <tr><td>{@code history}</td>
 *       <td>The same involvement test, restricted to RESOLVED or CLOSED.</td></tr>
 * </table>
 *
 * <p>Note that these do <b>not</b> partition the open set: {@code assigned-to-me} is broader than
 * "assigned to me" because of the last-replier clause, and {@code unassigned} overlaps neither.
 * That is the legacy behaviour of {@code assigned-to-me} preserved deliberately — widening it
 * would change what an employee already sees.
 *
 * <p>They are views, not a partition, so the counts in {@code /inbox/counts} may legitimately sum
 * to more than the number of open conversations. A client that presents them as mutually exclusive
 * buckets is drawing the wrong conclusion from correct numbers.
 */
public enum InboxFilter {
    /** The work queue. OPEN/PENDING with no assignee and no involvement test. */
    UNASSIGNED("unassigned"),
    /** OPEN/PENDING, anyone's. The supervisor's whole load. */
    ALL_OPEN("all-open"),
    /** OPEN/PENDING, owned by somebody other than the caller. */
    ASSIGNED_TO_OTHERS("assigned-to-others"),
    /** OPEN/PENDING and the caller is involved. The legacy view, no longer the default. */
    ASSIGNED_TO_ME("assigned-to-me"),
    /** RESOLVED/CLOSED and the caller is involved. */
    HISTORY("history");

    /**
     * The view an inbox shows when no {@code filter} is sent.
     *
     * <p>Was {@link #ASSIGNED_TO_ME}; changed to {@link #UNASSIGNED} by orchestrator ruling. See
     * {@code docs/migration/adr/0015-inbox-default-filter.md} for the reasoning and for the
     * compatibility consequence during the Phase-12 dual run.
     *
     * <p>It lives here rather than in a controller or a request record because both of those need
     * it and a literal repeated in two layers is two places for them to disagree. The rule that
     * matters is narrow: a default must never be able to hide work from every employee at once.
     */
    public static final InboxFilter DEFAULT = UNASSIGNED;

    private final String value;

    InboxFilter(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static InboxFilter fromValue(String value) {
        for (InboxFilter filter : values()) {
            if (filter.value.equals(value)) {
                return filter;
            }
        }
        throw new IllegalArgumentException(
            "Unknown inbox filter: " + value + "; accepted values are " + acceptedValues());
    }

    /**
     * Every accepted wire value, in declaration order.
     *
     * <p>Used in the refusal message for an unknown value, so the caller is told what it may send
     * rather than only what it may not — and so the list cannot disagree with the enum, because it
     * is derived from it.
     */
    public static List<String> acceptedValues() {
        return List.of(values()).stream().map(InboxFilter::value).collect(Collectors.toList());
    }
}