package com.firstelement.crm.audit;

import com.firstelement.crm.persistence.JsonCodec;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the CRM audit stream into the legacy {@code audit_logs} table.
 *
 * <h2>Schema mapping</h2>
 *
 * <p>{@code audit_logs} is one of the two documented organisation-wide streams:
 * {@code projectId} is nullable and {@code actorType} is plain {@code text}. This writer
 * records {@code USER} for a human actor and {@code SYSTEM} when there is none, which is
 * what the legacy {@code AuditService} wrote and what every existing row reads as.
 *
 * <h2>REQUIRES_NEW, and never throws</h2>
 *
 * <p>Both properties come from Phase 3 and are repeated here because this is a second writer
 * into the same table:
 *
 * <ul>
 *   <li>An audit row must survive the rollback of the business transaction it describes —
 *       a trail that vanishes with the failed operation is not a trail.</li>
 *   <li>A failing audit insert must not roll back or fail an action the user already
 *       completed. It is logged at ERROR and swallowed. That is the same trade the legacy
 *       service made, and it is the reason this is an explicit component rather than a hard
 *       dependency of every domain service.</li>
 * </ul>
 */
@Component
public class CrmAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(CrmAuditWriter.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public CrmAuditWriter(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(CrmAuditRecord record) {
        if (record == null) {
            return;
        }
        try {
            jdbc.update("""
                insert into audit_logs ("id", "organizationId", "projectId", "actorUserId",
                    "actorType", "action", "resourceType", "resourceId", "metadata",
                    "ipAddress", "createdAt")
                values (:id, :org, :project, :actorUserId, :actorType, :action, :resourceType,
                    :resourceId, CAST(:metadata AS jsonb), :ip, :createdAt)
                """,
                new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("org", record.organizationId())
                    .addValue("project", record.projectId())
                    .addValue("actorUserId", record.actorUserId())
                    .addValue("actorType", record.actorUserId() == null ? "SYSTEM" : "USER")
                    .addValue("action", record.action())
                    .addValue("resourceType", record.resourceType())
                    .addValue("resourceId", record.resourceId())
                    .addValue("metadata", json.write(record.metadata()))
                    .addValue("ip", record.ipAddress())
                    .addValue("createdAt", LocalDateTime.now()));
        } catch (RuntimeException e) {
            log.error("Failed to write CRM audit record action={} resourceType={} org={}",
                record.action(), record.resourceType(), record.organizationId(), e);
        }
    }

    /** Convenience overload for callers that only have a timestamp to record. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID organizationId,
                       UUID projectId,
                       UUID actorUserId,
                       String action,
                       String resourceType,
                       UUID resourceId,
                       java.util.Map<String, Object> metadata,
                       String ipAddress) {
        record(CrmAuditRecord.of(organizationId, projectId, actorUserId, action, resourceType,
            resourceId, metadata, ipAddress));
    }

    /**
     * Documents the timestamp mapping for the {@code createdAt} column.
     *
     * <p>{@code audit_logs."createdAt"} is {@code timestamp(3) without time zone}, so the
     * bound value is a {@link LocalDateTime} (the driver accepts a {@link Timestamp} view of
     * it); an {@code Instant} would apply a zone shift and land in the wrong day.
     */
    @SuppressWarnings("unused")
    private static LocalDateTime timestampContract(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}