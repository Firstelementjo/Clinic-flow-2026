package com.firstelement.crm.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.InboxFilter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The saved-view filter definitions, with no database.
 *
 * <p>The legacy file is explicit that this logic must be a pure function: "these are the
 * definitions an operator's whole day depends on — 'unassigned' must mean the same thing in the
 * list, the counts and the analytics — and a pure function is testable without a database."
 * These are the tests that keep that promise.
 *
 * <p>Expected SQL fragments are written out in full rather than matched loosely, because the
 * <em>shape</em> of the fragment is the contract: a filter that silently stops joining
 * {@code employees} still returns rows, just the wrong ones.
 */
class InboxFilterDefinitionTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("assigned-to-me is OPEN or PENDING and the caller is involved")
    void assignedToMe() {
        String predicate = InboxFilterDefinition.predicate(InboxFilter.ASSIGNED_TO_ME, USER, null,
            null, InboxFilterDefinition.CUSTOMER_ALIAS);

        assertThat(predicate).isEqualTo(
            "(c.\"status\" in (cast('OPEN' as \"ConversationStatus\"),"
                + " cast('PENDING' as \"ConversationStatus\"))"
                + " and (e.\"userId\" = :inboxUserId or c.\"lastReplierUserId\" = :inboxUserId))");
    }

    @Test
    @DisplayName("history is the same involvement test restricted to RESOLVED or CLOSED")
    void history() {
        String predicate = InboxFilterDefinition.predicate(InboxFilter.HISTORY, USER, null, null,
            InboxFilterDefinition.CUSTOMER_ALIAS);

        assertThat(predicate).isEqualTo(
            "(c.\"status\" in (cast('RESOLVED' as \"ConversationStatus\"),"
                + " cast('CLOSED' as \"ConversationStatus\"))"
                + " and (e.\"userId\" = :inboxUserId or c.\"lastReplierUserId\" = :inboxUserId))");
    }

    @Test
    @DisplayName("no view adds no status restriction: the default inbox is every conversation")
    void noViewIsUnfiltered() {
        assertThat(InboxFilterDefinition.predicate(null, USER, null, null,
            InboxFilterDefinition.CUSTOMER_ALIAS)).isEmpty();
    }

    @Test
    @DisplayName("a channel narrows the row without touching the view")
    void channelIsAnded() {
        String predicate = InboxFilterDefinition.predicate(InboxFilter.ASSIGNED_TO_ME, USER,
            ChannelType.WHATSAPP, null, InboxFilterDefinition.CUSTOMER_ALIAS);

        // The view comes first and the channel is ANDed onto it, so the ordering of the two
        // fragments is part of the shape a reviewer reads.
        assertThat(predicate).isEqualTo(
            "(c.\"status\" in (cast('OPEN' as \"ConversationStatus\"),"
                + " cast('PENDING' as \"ConversationStatus\"))"
                + " and (e.\"userId\" = :inboxUserId or c.\"lastReplierUserId\" = :inboxUserId))"
                + " and c.\"channel\" = cast(:inboxChannel as \"ChannelType\")");
    }

    @Test
    @DisplayName("search covers ticket number, subject, CX id and any message body")
    void searchBranches() {
        String predicate = InboxFilterDefinition.predicate(null, USER, null, "  invoice  ",
            InboxFilterDefinition.CUSTOMER_ALIAS);

        assertThat(predicate)
            .contains("c.\"ticketNumber\" ilike :inboxSearch escape '\\'")
            .contains("c.\"subject\" ilike :inboxSearch escape '\\'")
            .contains("cu.\"cxId\" ilike :inboxSearch escape '\\'")
            .contains("exists (select 1 from messages m");

        // The message-body branch must carry the conversation's own scope, or a search could read
        // another tenant's message text.
        assertThat(predicate)
            .contains("m.\"organizationId\" = c.\"organizationId\"")
            .contains("m.\"projectId\" = c.\"projectId\"");
    }

    @Test
    @DisplayName("the CX branch is omitted when the statement has no customers alias")
    void searchWithoutCustomerAlias() {
        String predicate = InboxFilterDefinition.predicate(null, USER, null, "abc", null);

        assertThat(predicate).doesNotContain("cu.");
        assertThat(predicate).contains("c.\"ticketNumber\"");
    }

    @Test
    @DisplayName("LIKE wildcards in a search term are escaped, not honoured")
    void wildcardsAreEscaped() {
        assertThat(LikePattern.contains("100%")).isEqualTo("%100\\%%");
        assertThat(LikePattern.contains("a_b")).isEqualTo("%a\\_b%");
        assertThat(LikePattern.contains("c\\d")).isEqualTo("%c\\\\d%");
        assertThat(LikePattern.escape("plain")).isEqualTo("plain");
    }

    @Test
    @DisplayName("parameters are bound, never interpolated")
    void parametersAreBound() {
        Map<String, Object> parameters = InboxFilterDefinition.parameters(InboxFilter.ASSIGNED_TO_ME,
            OTHER, ChannelType.TELEGRAM, " 50% off ");

        assertThat(parameters).containsEntry("inboxUserId", OTHER);
        assertThat(parameters).containsEntry("inboxChannel", "TELEGRAM");
        assertThat(parameters).containsEntry("inboxSearch", "%50\\% off%");
    }

    @Test
    @DisplayName("no view, no channel and no search bind nothing")
    void noParametersWhenNothingFilters() {
        assertThat(InboxFilterDefinition.parameters(null, USER, null, "   ")).isEmpty();
    }

    @Test
    @DisplayName("the fragment never mentions a tenant column")
    void fragmentIsTenantFree() {
        for (InboxFilter filter : InboxFilter.values()) {
            String predicate = InboxFilterDefinition.predicate(filter, USER, ChannelType.MOCK,
                "x", InboxFilterDefinition.CUSTOMER_ALIAS);
            assertThat(predicate).doesNotContain("__fe_crm_organization_id");
            assertThat(predicate).doesNotContain("__fe_crm_project_ids");
        }
    }

    // ==================================================================
    // Phase 15: the three views that let the work queue be reachable
    // ==================================================================

    @Test
    @DisplayName("unassigned is the canonical rule, with no involvement test at all")
    void unassignedIsTheCanonicalRule() {
        String predicate = InboxFilterDefinition.predicate(InboxFilter.UNASSIGNED, USER, null, null,
            InboxFilterDefinition.CUSTOMER_ALIAS);

        // Byte-identical to the constant the legacy /inbox/unassigned route now delegates to.
        // Equality here is not tidiness: it is the reconciliation requirement, asserted in a place
        // that needs no database and therefore cannot be skipped for wanting Docker.
        assertThat(predicate).isEqualTo("(" + InboxFilterDefinition.UNASSIGNED_ACTIVE + ")");

        // "no involvement test" is load-bearing. Adding a last-replier clause would hide a
        // conversation from the work queue for the very person who last touched it.
        assertThat(predicate).doesNotContain(":inboxUserId");
        assertThat(predicate).doesNotContain("lastReplierUserId");
        assertThat(predicate).contains("c.\"assignedEmployeeId\" is null");
    }

    @Test
    @DisplayName("all-open is OPEN or PENDING and says nothing about ownership")
    void allOpenIgnoresOwnership() {
        String predicate = InboxFilterDefinition.predicate(InboxFilter.ALL_OPEN, USER, null, null,
            InboxFilterDefinition.CUSTOMER_ALIAS);

        assertThat(predicate).isEqualTo(
            "(c.\"status\" in (cast('OPEN' as \"ConversationStatus\"),"
                + " cast('PENDING' as \"ConversationStatus\")))");
        assertThat(predicate).doesNotContain("assignedEmployeeId");
        assertThat(predicate).doesNotContain(":inboxUserId");
    }

    @Test
    @DisplayName("assigned-to-others needs an owner, and the owner must not be the caller")
    void assignedToOthersExcludesTheCaller() {
        String predicate = InboxFilterDefinition.predicate(InboxFilter.ASSIGNED_TO_OTHERS, USER,
            null, null, InboxFilterDefinition.CUSTOMER_ALIAS);

        assertThat(predicate).isEqualTo(
            "(c.\"status\" in (cast('OPEN' as \"ConversationStatus\"),"
                + " cast('PENDING' as \"ConversationStatus\"))"
                + " and e.\"userId\" is not null and e.\"userId\" <> :inboxUserId)");

        // `is not null` is what keeps an unassigned conversation out of "other people's" load:
        // nobody owns it, so it belongs to the work queue and not to a supervisor's peer review.
        assertThat(predicate).contains("e.\"userId\" is not null");
        assertThat(InboxFilterDefinition.predicate(InboxFilter.ASSIGNED_TO_OTHERS, USER, null, null,
            InboxFilterDefinition.CUSTOMER_ALIAS)).contains("<> :inboxUserId");
    }

    @Test
    @DisplayName("exactly the three ownership-by-caller views bind the user id")
    void onlyCallerSensitiveViewsBindTheUserId() {
        assertThat(InboxFilter.ASSIGNED_TO_ME).satisfies(
            f -> assertThat(InboxFilterDefinition.parameters(f, USER, null, null))
                .containsEntry("inboxUserId", USER));
        assertThat(InboxFilter.HISTORY).satisfies(
            f -> assertThat(InboxFilterDefinition.parameters(f, USER, null, null))
                .containsEntry("inboxUserId", USER));
        assertThat(InboxFilter.ASSIGNED_TO_OTHERS).satisfies(
            f -> assertThat(InboxFilterDefinition.parameters(f, USER, null, null))
                .containsEntry("inboxUserId", USER));

        // The work queue is ownership, not involvement, and a supervisor's load is everyone's.
        // Binding a user id into a predicate that ignores it invites exactly the wrong reading.
        assertThat(InboxFilter.UNASSIGNED).satisfies(
            f -> assertThat(InboxFilterDefinition.parameters(f, USER, null, null))
                .doesNotContainKey("inboxUserId"));
        assertThat(InboxFilter.ALL_OPEN).satisfies(
            f -> assertThat(InboxFilterDefinition.parameters(f, USER, null, null))
                .doesNotContainKey("inboxUserId"));
    }

    @Test
    @DisplayName("every view that mentions the employee alias is matched by the join the caller adds")
    void employeeReferenceImpliesTheJoinIsPresent() {
        for (InboxFilter filter : InboxFilter.values()) {
            String predicate = InboxFilterDefinition.predicate(filter, USER, null, null,
                InboxFilterDefinition.CUSTOMER_ALIAS);
            if (predicate.contains("e.\"userId\"")) {
                assertThat(InboxFilterDefinition.EMPLOYEE_JOIN)
                    .as("%s references e.\"userId\", so both repository statements must add the "
                        + "employees join", filter.value())
                    .contains("left join employees e on e.\"id\" = c.\"assignedEmployeeId\"");
            }
        }
    }

    // ==================================================================
    // the vocabulary itself
    // ==================================================================

    @Test
    @DisplayName("the accepted wire values are the five decided ones, in declaration order")
    void acceptedValuesAreTheDecidedVocabulary() {
        assertThat(InboxFilter.acceptedValues()).containsExactly(
            "unassigned", "all-open", "assigned-to-others", "assigned-to-me", "history");
    }

    @Test
    @DisplayName("every accepted value round-trips through fromValue, and nothing else does")
    void fromValueRoundTripsAndRefuses() {
        for (InboxFilter filter : InboxFilter.values()) {
            assertThat(InboxFilter.fromValue(filter.value())).isSameAs(filter);
        }

        // The kebab-case wire values must NOT be the Java constant names: springdoc would publish
        // the constants, and a client generated from that would send ?filter=UNASSIGNED.
        assertThat(InboxFilter.acceptedValues()).doesNotContain("UNASSIGNED");

        assertThatThrownBy(() -> InboxFilter.fromValue("everything"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("everything")
            .hasMessageContaining("unassigned");
        // Case-sensitivity is deliberate: the wire values are exact, and a permissive matcher would
        // mean the document and the server accepted different vocabularies.
        assertThatThrownBy(() -> InboxFilter.fromValue("UNASSIGNED"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}