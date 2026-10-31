package com.firstelement.crm.audit;

import java.util.Map;
import java.util.UUID;

/**
 * One CRM audit record, written to the legacy {@code audit_logs} table.
 *
 * <h2>Action names are the legacy ones, verbatim</h2>
 *
 * <p>{@code CUSTOMER_DATA_UPDATED}, {@code EMPLOYEE_REPLIED},
 * {@code EMPLOYEE_ASSIGNED_CONVERSATION}, {@code EMPLOYEE_RESOLVED_CONVERSATION} and
 * {@code ADMIN_CHANGED_ESCALATION_RULE} are the exact {@code AuditAction} values the
 * NestJS {@code AuditService} wrote. The strangler window needs old and new evidence to be
 * joinable, so the vocabulary is not "improved".
 *
 * <h2>Metadata carries field names, never field values</h2>
 *
 * <p>The legacy comment on the customer update says it outright: "Field names only — customer
 * data does not belong in the audit log". This record therefore accepts a caller-supplied
 * metadata map and the caller is responsible for keeping values out of it; the two call
 * sites that exist pass {@code changedFields}, a message id, an employee id, or a case
 * outcome.
 *
 * @param organizationId the tenant; never null
 * @param projectId      the project, or {@code null} for an organization-level action
 * @param actorUserId    the acting user, or {@code null} for a system/worker actor
 * @param action         the legacy {@code AuditAction} value
 * @param resourceType   e.g. {@code "Conversation"}, {@code "Customer"}
 * @param resourceId     the affected row's id, as text (the column is {@code text})
 * @param metadata       non-sensitive attributes
 * @param ipAddress      the client address, or {@code null}
 */
public record CrmAuditRecord(UUID organizationId,
                             UUID projectId,
                             UUID actorUserId,
                             String action,
                             String resourceType,
                             String resourceId,
                             Map<String, Object> metadata,
                             String ipAddress) {

    /** Action names, kept identical to {@code packages/shared/src/enums.ts}. */
    public static final String CUSTOMER_DATA_UPDATED = "CUSTOMER_DATA_UPDATED";
    public static final String EMPLOYEE_REPLIED = "EMPLOYEE_REPLIED";
    public static final String EMPLOYEE_ASSIGNED_CONVERSATION = "EMPLOYEE_ASSIGNED_CONVERSATION";
    public static final String EMPLOYEE_RESOLVED_CONVERSATION = "EMPLOYEE_RESOLVED_CONVERSATION";
    public static final String EMPLOYEE_TOOK_CONVERSATION = "EMPLOYEE_TOOK_CONVERSATION";
    public static final String ADMIN_CHANGED_ESCALATION_RULE = "ADMIN_CHANGED_ESCALATION_RULE";

    public CrmAuditRecord {
        if (organizationId == null) {
            throw new IllegalArgumentException("A CRM audit record must carry its organizationId");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("A CRM audit record must carry an action");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static CrmAuditRecord of(UUID organizationId,
                                     UUID projectId,
                                     UUID actorUserId,
                                     String action,
                                     String resourceType,
                                     UUID resourceId,
                                     Map<String, Object> metadata,
                                     String ipAddress) {
        return new CrmAuditRecord(organizationId, projectId, actorUserId, action, resourceType,
            resourceId == null ? null : resourceId.toString(), metadata, ipAddress);
    }
}