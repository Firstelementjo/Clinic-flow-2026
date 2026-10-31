package com.firstelement.admin.audit;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One fact to write onto one of the three append-only Admin streams.
 *
 * <h2>Why a value and not three writer signatures</h2>
 *
 * <p>The three tables in {@code V20261005050000} are deliberately near-identical column lists, and
 * an operator action usually belongs on exactly one of them. This record is the shared shape, and
 * each writer projects it onto its own table — including the fields that table does not have, which
 * are then simply not written. A single writer with three methods would have made "which table does
 * this field go to" a question inside a method body; three writers over one record keeps it in the
 * projection.
 *
 * <h2>What may never be in {@code metadata}</h2>
 *
 * <p>Ids, counts, booleans, policy names and stable reason codes. Never a password, a token, a
 * cookie, a connector credential, a customer message, or a raw tool argument.
 * {@link com.firstelement.security.event.SecurityEventSanitizer} is applied by the writers as a
 * mechanical backstop, so a future caller who forgets the rule produces a redacted row rather than a
 * leak — and a visible {@code [redacted]} in the row, which is itself a signal.
 */
public record AuditWrite(
    UUID organizationId,
    UUID projectId,
    UUID actorUserId,
    AuditVocabularies.AuditPrincipalType principalType,
    String principalName,
    String action,
    String resourceType,
    String resourceId,
    AuditVocabularies.AuditOutcome outcome,
    Map<String, Object> metadata,
    String ipAddress,
    String userAgent,
    String correlationId,
    String causationId,
    String retentionClass,
    String denyReason,
    String failureCode,
    String detail,
    String queueName,
    String jobId,
    UUID outboxEventId,
    UUID jobAttemptId,
    Integer latencyMs,
    LocalDateTime occurredAt
) {

    public AuditWrite {
        if (organizationId == null) {
            // Every table in this migration has organizationId NOT NULL (FR-1.2). A row nobody can
            // filter by tenant is the one row an audit view must never contain, so this refuses
            // rather than inventing a tenant.
            throw new IllegalArgumentException(
                "An audit row must carry the tenant it belongs to; organizationId is NOT NULL on "
                    + "admin_audit_events, security_events and job_integration_events.");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("An audit row must name what happened (action)");
        }
        if (principalType == null) {
            throw new IllegalArgumentException("principalType is NOT NULL on every audit stream");
        }
        if (principalName == null || principalName.isBlank()) {
            throw new IllegalArgumentException("principalName is NOT NULL on every audit stream");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    /** A builder, because a dozen nullable fields read badly as a constructor call. */
    public static Builder builder(UUID organizationId) {
        return new Builder(organizationId);
    }

    /** Fluent construction; only the fields a stream actually has are set. */
    public static final class Builder {

        private final UUID organizationId;
        private UUID projectId;
        private UUID actorUserId;
        private AuditVocabularies.AuditPrincipalType principalType =
            AuditVocabularies.AuditPrincipalType.SYSTEM;
        private String principalName = "SYSTEM";
        private String action;
        private String resourceType = "Unknown";
        private String resourceId;
        private AuditVocabularies.AuditOutcome outcome = AuditVocabularies.AuditOutcome.SUCCESS;
        private Map<String, Object> metadata = Map.of();
        private String ipAddress;
        private String userAgent;
        private String correlationId;
        private String causationId;
        private String retentionClass = AuditVocabularies.UNDECLARED_RETENTION_CLASS;
        private String denyReason;
        private String failureCode;
        private String detail;
        private String queueName;
        private String jobId;
        private UUID outboxEventId;
        private UUID jobAttemptId;
        private Integer latencyMs;
        private LocalDateTime occurredAt;

        private Builder(UUID organizationId) {
            this.organizationId = organizationId;
        }

        public Builder projectId(UUID value) {
            this.projectId = value;
            return this;
        }

        public Builder actorUserId(UUID value) {
            this.actorUserId = value;
            return this;
        }

        public Builder principalType(AuditVocabularies.AuditPrincipalType value) {
            this.principalType = value;
            return this;
        }

        public Builder principalName(String value) {
            this.principalName = value;
            return this;
        }

        public Builder action(String value) {
            this.action = value;
            return this;
        }

        public Builder resourceType(String value) {
            this.resourceType = value;
            return this;
        }

        public Builder resourceId(String value) {
            this.resourceId = value;
            return this;
        }

        public Builder outcome(AuditVocabularies.AuditOutcome value) {
            this.outcome = value;
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            this.metadata = value == null ? Map.of() : value;
            return this;
        }

        public Builder detail(String value) {
            this.detail = value;
            return this;
        }

        public Builder ipAddress(String value) {
            this.ipAddress = value;
            return this;
        }

        public Builder userAgent(String value) {
            this.userAgent = value;
            return this;
        }

        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        public Builder causationId(String value) {
            this.causationId = value;
            return this;
        }

        public Builder retentionClass(String value) {
            this.retentionClass = value == null || value.isBlank()
                ? AuditVocabularies.UNDECLARED_RETENTION_CLASS : value;
            return this;
        }

        public Builder denyReason(String value) {
            this.denyReason = value;
            return this;
        }

        public Builder failureCode(String value) {
            this.failureCode = value;
            return this;
        }

        public Builder queueName(String value) {
            this.queueName = value;
            return this;
        }

        public Builder jobId(String value) {
            this.jobId = value;
            return this;
        }

        public Builder outboxEventId(UUID value) {
            this.outboxEventId = value;
            return this;
        }

        public Builder jobAttemptId(UUID value) {
            this.jobAttemptId = value;
            return this;
        }

        public Builder latencyMs(Integer value) {
            this.latencyMs = value;
            return this;
        }

        public Builder occurredAt(LocalDateTime value) {
            this.occurredAt = value;
            return this;
        }

        public AuditWrite build() {
            return new AuditWrite(organizationId, projectId, actorUserId, principalType,
                principalName, action, resourceType, resourceId, outcome, metadata, ipAddress,
                userAgent, correlationId, causationId, retentionClass, denyReason, failureCode,
                detail, queueName, jobId, outboxEventId, jobAttemptId, latencyMs, occurredAt);
        }
    }
}