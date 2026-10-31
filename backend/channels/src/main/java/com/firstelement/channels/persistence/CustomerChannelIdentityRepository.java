package com.firstelement.channels.persistence;

import com.firstelement.crm.query.CrmQueryScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The customer's handle on a channel, resolved from {@code customer_identities} under tenant scope.
 *
 * <h2>Why the outbound path must ask for this</h2>
 *
 * <p>The legacy dispatcher read it explicitly and failed the send when it was absent: "Customer has
 * no {@code channel} handle", non-retryable. That is the correct behaviour — a customer who has
 * never messaged on WhatsApp has no WhatsApp id, and inventing one produces a send the provider
 * rejects with an opaque error.
 *
 * <h2>Why the earliest identity wins</h2>
 *
 * <p>{@code order by "createdAt" asc} is the legacy rule and it is the right one: the identity the
 * customer first gave is the one they have verified in the conversation; a later one may be an
 * unverified claim by someone else.
 */
@Repository
public class CustomerChannelIdentityRepository {

    private static final String FIND = """
        select i."externalId", i."isVerified", i."createdAt"
          from customer_identities i
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerChannelIdentityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The customer's handle on {@code channel}.
     *
     * <p>The predicate goes through {@link CrmQueryScope}, so a customer id from a job payload is
     * scoped by the resolved tenant rather than trusted.
     */
    public Optional<ChannelIdentity> find(CrmQueryScope scope, UUID customerId, String channel) {
        CrmQueryScope.Statement statement = scope.select(FIND, "i",
            "i.\"customerId\" = :customerId",
            CrmQueryScope.params("customerId", customerId, "channel", channel))
            .orderedBy("i.\"createdAt\" asc", 1);
        return jdbc.query(statement.sql(), statement.parameters(), (rs, rowNum) -> new ChannelIdentity(
            rs.getString("externalId"), rs.getBoolean("isVerified"),
            rs.getObject("createdAt", java.time.LocalDateTime.class))).stream().findFirst();
    }

    /**
     * One customer's identity on one platform.
     *
     * @param verified whether the platform vouches for it; a phone number on WhatsApp is a
     *                 platform-vouched signal, which is why identity resolution trusts it
     */
    public record ChannelIdentity(String externalId, boolean verified,
                                  java.time.LocalDateTime createdAt) {
    }
}
