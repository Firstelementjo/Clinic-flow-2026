package com.firstelement.crm.persistence;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.query.CrmQueryScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code customer_identities} — the channel handles a person writes from.
 *
 * <h2>Identity resolution is a database question, not a service question</h2>
 *
 * <p>{@code customer_identities_projectId_platform_externalId_key} is the only thing that can
 * answer "have I already met this handle?". The legacy resolver's own comment is the reason
 * this class is shaped the way it is: the {@code SELECT} exists to decide
 * create-vs-attach, but the <em>unique index</em> is what makes concurrent deliveries safe, so
 * a {@link DuplicateKeyException} is translated rather than swallowed.
 */
@Repository
public class CustomerIdentityRepository {

    private static final String SELECT = """
        select i."id", i."organizationId", i."projectId", i."customerId", i."platform",
               i."externalId", i."displayName", i."isVerified", i."createdAt", i."updatedAt"
        from customer_identities i
        """;

    static final RowMapper<IdentityRow> ROW = (rs, rowNum) -> new IdentityRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getObject("customerId", UUID.class),
        rs.getString("platform"),
        rs.getString("externalId"),
        rs.getString("displayName"),
        rs.getBoolean("isVerified"),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getObject("updatedAt", LocalDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerIdentityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The handle for (project, channel, external id), or empty. */
    public Optional<IdentityRow> findByHandle(CrmQueryScope scope,
                                             ChannelType platform,
                                             String externalId) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "i",
            "i.\"platform\" = cast(:platform as \"ChannelType\") and i.\"externalId\" = :externalId",
            CrmQueryScope.params("platform", platform.name(), "externalId", externalId));
        return jdbc.query(statement.sql(), statement.parameters(), ROW).stream().findFirst();
    }

    /**
     * Attaches a handle to a customer.
     *
     * @throws DuplicateKeyException when a concurrent delivery attached the same handle first;
     *         the caller re-reads and returns the winner's customer
     */
    public IdentityRow insert(CrmQueryScope scope,
                              UUID identityId,
                              UUID customerId,
                              ChannelType platform,
                              String externalId,
                              String displayName,
                              boolean isVerified,
                              String rawProfileJson,
                              LocalDateTime now) {
        try {
            jdbc.update("""
                insert into customer_identities ("id", "organizationId", "projectId", "customerId",
                    "platform", "externalId", "displayName", "isVerified", "rawProfile",
                    "createdAt", "updatedAt")
                values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :customerId,
                    cast(:platform as "ChannelType"), :externalId, :displayName, :isVerified,
                    CAST(:rawProfile AS jsonb), :now, :now)
                """, scope.bind(CrmQueryScope.params(
                    "id", identityId, "customerId", customerId, "platform", platform.name(),
                    "externalId", externalId, "displayName", displayName,
                    "isVerified", isVerified, "rawProfile", rawProfileJson, "now", now)));
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdentityException(externalId);
        }
        return new IdentityRow(identityId, scope.organizationId(), scope.projectId(), customerId,
            platform.name(), externalId, displayName, isVerified, now, now);
    }

    /** Every handle this customer has. Used by the identity resolver's debug view. */
    public List<IdentityRow> forCustomer(CrmQueryScope scope, UUID customerId) {
        CrmQueryScope.Statement statement = scope.select(SELECT, "i", "i.\"customerId\" = :customerId",
            CrmQueryScope.params("customerId", customerId));
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    /** Another process attached the same handle first. */
    public static class DuplicateIdentityException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String externalId;

        public DuplicateIdentityException(String externalId) {
            super("This channel handle is already attached to a customer");
            this.externalId = externalId;
        }

        public String externalId() {
            return externalId;
        }
    }

    /** One stored handle. */
    public record IdentityRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              UUID customerId,
                              String platform,
                              String externalId,
                              String displayName,
                              boolean isVerified,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
    }
}