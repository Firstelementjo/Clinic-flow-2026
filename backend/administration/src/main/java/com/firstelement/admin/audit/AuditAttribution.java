package com.firstelement.admin.audit;

import com.firstelement.security.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The attribution every Admin audit row carries, resolved once.
 *
 * <h2>Why the actor is never taken from a request</h2>
 *
 * <p>{@code AuditWrite.principalName} and {@code actorUserId} are derived here from the resolved
 * {@link TenantContext}: the context is what {@code TenantContextFilter} bound after the
 * authentication filter resolved the session against {@code sessions}, so both values came from the
 * database. A controller that had an {@code X-Actor-Id} header would be an impersonation surface,
 * and there is no such parameter anywhere in this module.
 *
 * <h2>Why the name is a label, not an email</h2>
 *
 * <p>{@link TenantContext#auditActorLabel()} is {@code USER:<uuid>} or
 * {@code SERVICE:<name>} — an opaque, non-disclosing identity. The admin stream is read by people
 * who are not necessarily entitled to the {@code users.email} of everybody who ever acted, and an
 * audit table is exactly the place where that column would leak most usefully to an attacker.
 */
public final class AuditAttribution {

    private AuditAttribution() {
    }

    /** A builder pre-filled with the acting principal and the request's correlation id. */
    public static AuditWrite.Builder forActor(UUID organizationId, TenantContext context) {
        AuditWrite.Builder builder = AuditWrite.builder(organizationId);
        if (context == null) {
            return builder.principalType(AuditVocabularies.AuditPrincipalType.SYSTEM)
                .principalName("SYSTEM:unresolved")
                .correlationId(MDC.get("correlationId"));
        }
        return builder
            .principalType(AuditVocabularies.AuditPrincipalType.of(context.principalType()))
            .principalName(context.auditActorLabel())
            .actorUserId(context.userId())
            .correlationId(MDC.get("correlationId"));
    }

    /**
     * The current UTC wall clock, in the shape the schema stores.
     *
     * <p>{@code timestamp(3) without time zone}, stored and compared in UTC (ADR-0002). Local host
     * time would put every row three hours out on a Riyadh-configured machine, which is the exact
     * failure {@code UtcTime} exists to prevent in the worker runtime; this module does not depend
     * on that class for a single field, so it names the zone itself rather than reaching across a
     * module boundary for a clock.
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Metadata with the mechanical sanitizer applied.
     *
     * <p>The rule is "ids, counts, booleans, policy names". The sanitizer replaces any value whose
     * <em>key</em> looks credential-shaped, so the guarantee is that a caller who forgets the rule
     * writes {@code [redacted]} rather than a secret.
     */
    public static Map<String, Object> safe(Map<String, Object> metadata) {
        return com.firstelement.security.event.SecurityEventSanitizer.sanitize(metadata);
    }
}