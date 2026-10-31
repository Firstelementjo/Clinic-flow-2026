package com.firstelement.inbox.persistence;

import com.firstelement.crm.query.CrmQueryScope;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one {@code channel_connections} read the CRM lifecycle needs: "is there an enabled MOCK
 * connection in this project?".
 *
 * <h2>Why this lives in the inbox module</h2>
 *
 * <p>Channels are Phase 5's ownership. This is a single scoped {@code exists} read that the
 * development inbound simulator needs to refuse to run against a project with no live mock
 * connector, and it reads nothing but the two columns the check needs. When Phase 5's connector
 * registry lands it replaces this probe; the call site does not change.
 *
 * <h2>What is deliberately not read</h2>
 *
 * <p>{@code credentials} and {@code webhookSecret}. This class has no statement that selects
 * either, so a future edit that widens the projection cannot surface a connector secret by
 * accident.
 */
@Repository
public class MockChannelProbe {

    private final NamedParameterJdbcTemplate jdbc;

    public MockChannelProbe(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether {@code projectId} has an enabled MOCK connection. */
    public boolean hasEnabledMockConnection(CrmQueryScope scope, UUID projectId) {
        // Built through `scope.select` rather than by hand-writing a WHERE clause.
        //
        // The previous version bound `scope.bind(...)` but typed its own `where`, so the
        // bound organization predicate was never referenced by the SQL and the tenant
        // filter was silently dropped -- the statement bound the scope and then ignored
        // it. Nothing caught it because the one caller also validated through
        // `CrmQueryScope.forProject` first, so the answer was correct by accident rather
        // than by construction.
        //
        // `select` appends the tenant predicate itself and has no slot for a hand-written
        // `where`, so the filter cannot be left out by a later edit. That is the whole
        // point of routing through it rather than adding the missing `and`.
        CrmQueryScope.Statement statement = scope.select(
            "select count(*) from channel_connections ch",
            "ch",
            """
            ch."projectId" = :projectId
              and ch."channel" = cast('MOCK' as "ChannelType")
              and ch."isEnabled" = true
            """,
            CrmQueryScope.params("projectId", projectId));
        Integer count = jdbc.queryForObject(statement.sql(), statement.parameters(), Integer.class);
        return count != null && count > 0;
    }
}