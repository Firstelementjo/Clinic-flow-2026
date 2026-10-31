package com.firstelement.admin.audit;

import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the three append-only Admin streams of {@code V20261005050000}.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>ADR-0005 §5 recorded the audit/security split as "by {@code SecurityEventType} convention and
 * must not be described as a physical separation", because the migration that would make it
 * physical was still an inert {@code .sql.todo}. That migration has since been promoted and
 * verified, so the separation is now physical and something has to write to it. Until now the only
 * writer was {@code AuditLogSecurityEventPublisher} in {@code :backend:identity-access}, which writes
 * the legacy single {@code audit_logs} table; Phase 3 owns that class and this phase does not
 * change it. What this class adds is a writer for the <em>new</em> streams, so every action taken
 * through the Admin surface lands on the stream its audience is entitled to read.
 *
 * <h2>Three tables, one method each, no dispatcher</h2>
 *
 * <p>There is no {@code write(event)} that picks a table by a string. A caller says which stream it
 * means by calling {@link #admin}, {@link #security} or {@link #jobIntegration}, and each method's
 * statement names its columns explicitly. "Which table does this go to" therefore appears in a
 * method signature rather than in a conditional, and a field only one table has is written only by
 * that table's method.
 *
 * <h2>Never throws</h2>
 *
 * <p>{@link Propagation#REQUIRES_NEW}, so the audit row survives a rollback of the business
 * transaction it describes — an audit trail that disappears with the failed operation is not a
 * trail — and a failure is logged at ERROR and swallowed, because a broken audit insert must not
 * roll back or fail a legitimate action the operator already completed. That is the same call the
 * legacy {@code AuditService} made, and it is why this is a component rather than a required
 * dependency of every service.
 */
@Component
public class AdminAuditStreamWriter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditStreamWriter.class);

    /**
     * {@code admin_audit_events}. Append-only: no {@code updatedAt} column exists, and the
     * post-condition guard in the migration fails the migration if one appears. Correcting an audit
     * event is appending a correction, not rewriting the event.
     */
    private static final String ADMIN_INSERT = """
        insert into admin_audit_events (
            "id", "organizationId", "projectId", "actorUserId", "principalType", "principalName",
            "action", "resourceType", "resourceId", "outcome", "metadata", "retentionClass",
            "ipAddress", "userAgent", "correlationId", "causationId", "occurredAt")
        values (
            :id, :org, :projectId, :actorUserId, cast(:principalType as "AuditPrincipalType"),
            :principalName, :action, :resourceType, :resourceId, cast(:outcome as "AuditOutcome"),
            cast(:metadata as jsonb), :retentionClass, :ipAddress, :userAgent, :correlationId,
            :causationId, :occurredAt)
        """;

    /** {@code security_events}. {@code projectId} is NOT NULL: a security decision protects a project. */
    private static final String SECURITY_INSERT = """
        insert into security_events (
            "id", "organizationId", "projectId", "actorUserId", "principalType", "principalName",
            "eventType", "outcome", "detail", "denyReason", "retentionClass", "ipAddress",
            "userAgent", "sessionId", "correlationId", "occurredAt")
        values (
            :id, :org, :projectId, :actorUserId, cast(:principalType as "AuditPrincipalType"),
            :principalName, :eventType, cast(:outcome as "AuditOutcome"), :detail, :denyReason,
            :retentionClass, :ipAddress, :userAgent, :sessionId, :correlationId, :occurredAt)
        """;

    /** {@code job_integration_events}. {@code projectId} is nullable: a worker serves the tenant. */
    private static final String JOB_INSERT = """
        insert into job_integration_events (
            "id", "organizationId", "projectId", "component", "componentType", "eventType",
            "outcome", "queueName", "jobId", "outboxEventId", "jobAttemptId", "correlationId",
            "detail", "failureCode", "latencyMs", "retentionClass", "occurredAt")
        values (
            :id, :org, :projectId, :component, cast(:componentType as "AuditPrincipalType"),
            :eventType, cast(:outcome as "AuditOutcome"), :queueName, :jobId, :outboxEventId,
            :jobAttemptId, :correlationId, :detail, :failureCode, :latencyMs, :retentionClass,
            :occurredAt)
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    private final RetentionClassResolver retentionClasses;

    public AdminAuditStreamWriter(NamedParameterJdbcTemplate jdbc,
                                  JsonCodec json,
                                  RetentionClassResolver retentionClasses) {
        this.jdbc = jdbc;
        this.json = json;
        this.retentionClasses = retentionClasses;
    }

    // ------------------------------------------------------------------
    // admin stream
    // ------------------------------------------------------------------

    /**
     * Records an administrative action.
     *
     * <p>The audience is whoever may read {@code AUDIT_LOG_READ}: organization administrators. It is
     * the right stream for "a person changed a configuration" and the wrong one for "a person was
     * denied", which belongs on the security stream where a security reviewer would look for it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void admin(AuditWrite write) {
        if (write == null) {
            throw new IllegalArgumentException("An audit write must not be null");
        }
        insert(ADMIN_INSERT, new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("org", write.organizationId())
            .addValue("projectId", write.projectId())
            .addValue("actorUserId", write.actorUserId())
            .addValue("principalType", write.principalType().name())
            .addValue("principalName", write.principalName())
            .addValue("action", write.action())
            .addValue("resourceType", write.resourceType())
            .addValue("resourceId", write.resourceId())
            .addValue("outcome", write.outcome().label())
            .addValue("metadata", json.write(AuditAttribution.safe(write.metadata())))
            .addValue("retentionClass", write.retentionClass())
            .addValue("ipAddress", write.ipAddress())
            .addValue("userAgent", write.userAgent())
            .addValue("correlationId", write.correlationId())
            .addValue("causationId", write.causationId())
            .addValue("occurredAt", occurredAt(write)), "admin_audit_events", write);
    }

    // ------------------------------------------------------------------
    // security stream
    // ------------------------------------------------------------------

    /**
     * Records a security decision.
     *
     * <p>Refuses a write with no project: {@code security_events."projectId"} is NOT NULL by design
     * ("a security decision is about one project's data"), and inventing a project to satisfy the
     * constraint would attribute the event to a project that is not involved. A caller without one
     * records the fact on the admin stream with {@link AuditVocabularies.AuditOutcome#DENIED} instead,
     * which is what {@link #admin} is for.
     *
     * @return {@code false} when the event was not written because no project was in scope; the
     *         caller is expected to fall back rather than retry
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean security(AuditWrite write, AuditVocabularies.SecurityEventKind kind,
                            String sessionId) {
        if (write == null) {
            throw new IllegalArgumentException("An audit write must not be null");
        }
        if (write.projectId() == null) {
            return false;
        }
        if (write.outcome() == AuditVocabularies.AuditOutcome.DENIED
            && (write.denyReason() == null || write.denyReason().isBlank())) {
            // FR-6.9 and the migration's CHECK: a DENIED row must say why, or the denial stream is
            // a list of timestamps with no diagnostic value. Refused here rather than at the
            // constraint so the message names the caller.
            throw new IllegalArgumentException(
                "A DENIED security_events row must carry a denyReason; the schema CHECK requires it "
                    + "and a denial without a reason is not reviewable.");
        }
        insert(SECURITY_INSERT, new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("org", write.organizationId())
            .addValue("projectId", write.projectId())
            .addValue("actorUserId", write.actorUserId())
            .addValue("principalType", write.principalType().name())
            .addValue("principalName", write.principalName())
            .addValue("eventType", kind.label())
            .addValue("outcome", write.outcome().label())
            .addValue("detail", write.detail())
            .addValue("denyReason", write.denyReason())
            .addValue("retentionClass", write.retentionClass())
            .addValue("ipAddress", write.ipAddress())
            .addValue("userAgent", write.userAgent())
            .addValue("sessionId", sessionId)
            .addValue("correlationId", write.correlationId())
            .addValue("occurredAt", occurredAt(write)), "security_events", write);
        return true;
    }

    // ------------------------------------------------------------------
    // job / integration stream
    // ------------------------------------------------------------------

    /**
     * Records a worker or connector outcome.
     *
     * <p>{@code projectId} is nullable here (a worker serves the tenant, not a project), and a
     * {@code FAILED} outcome must carry a {@code failureCode} — the migration's CHECK refuses a
     * failure with no code, and "it failed" is not an operator-actionable line item.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void jobIntegration(AuditWrite write, AuditVocabularies.JobEventKind kind) {
        if (write == null) {
            throw new IllegalArgumentException("An audit write must not be null");
        }
        if (write.outcome() == AuditVocabularies.AuditOutcome.FAILED
            && (write.failureCode() == null || write.failureCode().isBlank())) {
            throw new IllegalArgumentException(
                "A FAILED job_integration_events row must carry a failureCode; an operator cannot "
                    + "act on 'something failed'.");
        }
        insert(JOB_INSERT, new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("org", write.organizationId())
            .addValue("projectId", write.projectId())
            // The "component" column is the thing that acted. For an Admin-driven worker action
            // that is the resource type, which is what makes the stream groupable.
            .addValue("component", write.resourceType())
            .addValue("componentType", write.principalType().name())
            .addValue("eventType", kind.label())
            .addValue("outcome", write.outcome().label())
            .addValue("queueName", write.queueName())
            .addValue("jobId", write.jobId())
            .addValue("outboxEventId", write.outboxEventId())
            .addValue("jobAttemptId", write.jobAttemptId())
            .addValue("correlationId", write.correlationId())
            .addValue("detail", write.detail())
            .addValue("failureCode", write.failureCode())
            .addValue("latencyMs", write.latencyMs())
            .addValue("retentionClass", write.retentionClass())
            .addValue("occurredAt", occurredAt(write)), "job_integration_events", write);
    }

    // ------------------------------------------------------------------
    // convenience for the common shape
    // ------------------------------------------------------------------

    /**
     * Records one Admin action with the retention class resolved from the tenant's configuration.
     *
     * <p>Preferred over the bare {@link #admin(AuditWrite)} because the retention class is a policy
     * question and answering it here means no caller can forget.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void adminAction(TenantScope scope, TenantContext context, AuditWrite.Builder builder) {
        String retentionClass = retentionClasses.resolve(scope, "ADMIN", "ADMIN_ACTIONS");
        AuditWrite.Builder stamped = builder.retentionClass(retentionClass);
        if (context != null) {
            stamped.principalType(AuditVocabularies.AuditPrincipalType.of(context.principalType()))
                .principalName(context.auditActorLabel())
                .actorUserId(context.userId());
        }
        admin(stamped.build());
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void insert(String sql, MapSqlParameterSource parameters, String table, AuditWrite write) {
        try {
            jdbc.update(sql, parameters);
        } catch (RuntimeException e) {
            // Never propagated. An action the operator already completed must not be undone because
            // the audit insert failed, and the error is logged loudly enough to be a page.
            log.error("Failed to write an audit row table={} organization={} action={} outcome={}",
                table, write.organizationId(), write.action(), write.outcome(), e);
        }
    }

    /**
     * The timestamp a row is stamped with.
     *
     * <p>{@code timestamp(3) without time zone}, stored and compared in UTC (ADR-0002). A caller
     * that supplies its own instant owns the correctness of it; the default is UTC rather than the
     * JVM's zone, which on a Riyadh-configured host would put every row three hours out of every
     * comparison the operator makes.
     */
    private static LocalDateTime occurredAt(AuditWrite write) {
        return write.occurredAt() == null ? AuditAttribution.now() : write.occurredAt();
    }
}