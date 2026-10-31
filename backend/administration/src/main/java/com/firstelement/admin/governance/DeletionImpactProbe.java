package com.firstelement.admin.governance;

import com.firstelement.security.TenantContext;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The impact count a privacy-deletion request must carry before it may be opened.
 *
 * <h2>Why this is a closed list and not a query</h2>
 *
 * <p>"No arbitrary database queries, table edits, shell execution, or secret display are allowed
 * from the web UI" (PROJECT_REQUIREMENTS FR-6). An impact count that accepted a table name from a
 * request would be an arbitrary query surface wearing a read-only costume: it would let a caller
 * enumerate any table in the database and, worse, discover which tables exist.
 *
 * <p>So the probes below are the <em>only</em> statements this class can execute, each one written
 * by hand, each one tenant-scoped, and each one counting rows rather than selecting them. Adding a
 * probe is a code change a reviewer reads; it is never a request.
 *
 * <h2>What is counted, and what is deliberately not</h2>
 *
 * <ul>
 *   <li>Sessions, notifications, project memberships, messages authored, workflow runs entered and
 *       audit rows naming the subject: the rows a person leaves behind. Counting is what makes a
 *       request reviewable — "one user" hides that it is nine tables and four thousand rows.</li>
 *   <li>Nothing from a table whose rows are customer <em>content</em> is ever selected. A count is
 *       a number; the read model returns numbers and ids only.</li>
 * </ul>
 */
@Repository
public class DeletionImpactProbe {

    /** One probe: a named, bounded, tenant-scoped {@code count(*)}. */
    public enum Probe {
        /** {@code sessions} for the subject: what a deactivation would revoke. */
        SESSIONS("sessions", "sessions"),
        /** {@code notifications} addressed to the subject. */
        NOTIFICATIONS("notifications", "notifications"),
        /** {@code project_memberships}: the reach the subject loses. */
        PROJECT_MEMBERSHIPS("project_memberships", "project memberships"),
        /** {@code messages} the subject authored. */
        MESSAGES("messages", "messages authored"),
        /** {@code workflow_runs} the subject drove. */
        WORKFLOW_RUNS("workflow_runs", "workflow runs"),
        /** {@code admin_audit_events} attributed to the subject: the evidence a deletion must keep. */
        ADMIN_AUDIT_EVENTS("admin_audit_events", "admin audit events"),
        /** {@code security_events} attributed to the subject. */
        SECURITY_EVENTS("security_events", "security events"),
        /** {@code audit_logs}: the legacy stream, still written during the strangler window. */
        LEGACY_AUDIT_LOGS("audit_logs", "legacy audit rows");

        private final String table;
        private final String label;

        Probe(String table, String label) {
            this.table = table;
            this.label = label;
        }

        public String table() {
            return table;
        }

        public String label() {
            return label;
        }
    }

    private static final MapSqlParameterSource NONE = new MapSqlParameterSource();

    private static final String SESSIONS = """
        select count(*) from sessions s
         where s."organizationId" = :__fe_tenant_organization_id and s."userId" = :subject
        """;

    private static final String NOTIFICATIONS = """
        select count(*) from notifications n
         where n."organizationId" = :__fe_tenant_organization_id and n."userId" = :subject
        """;

    private static final String MEMBERSHIPS = """
        select count(*) from project_memberships m
         where m."organizationId" = :__fe_tenant_organization_id and m."userId" = :subject
        """;

    private static final String MESSAGES = """
        select count(*) from messages m
         where m."organizationId" = :__fe_tenant_organization_id and m."authorUserId" = :subject
        """;

    private static final String WORKFLOW_RUNS = """
        select count(*) from workflow_runs r
         where r."organizationId" = :__fe_tenant_organization_id and r."principalUserId" = :subject
        """;

    private static final String ADMIN_AUDIT = """
        select count(*) from admin_audit_events a
         where a."organizationId" = :__fe_tenant_organization_id and a."actorUserId" = :subject
        """;

    private static final String SECURITY = """
        select count(*) from security_events s
         where s."organizationId" = :__fe_tenant_organization_id and s."actorUserId" = :subject
        """;

    private static final String LEGACY = """
        select count(*) from audit_logs a
         where a."organizationId" = :__fe_tenant_organization_id and a."actorUserId" = :subject
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeletionImpactProbe(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One probe's result.
     *
     * @param table the physical table, so an operator can see exactly what would be affected
     */
    public record ImpactRow(Probe probe, String table, long rows) {
    }

    /** The whole impact picture for one subject, plus the total. */
    public record Impact(List<ImpactRow> rows, long total) {

        public static Impact none() {
            return new Impact(List.of(), 0L);
        }
    }

    /**
     * Counts every probe for one subject.
     *
     * @param subject must already have been checked to be a user of this tenant; this method does
     *                not re-check, because the caller needs the count before deciding whether the
     *                subject is in scope
     */
    public Impact count(TenantQueryGuard guard, TenantContext caller, UUID subject) {
        TenantScope scope = guard.tenantScope(caller);
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(java.util.Map.of("subject", subject));
        List<ImpactRow> rows = new java.util.ArrayList<>();
        long total = 0L;
        for (Probe probe : Probe.values()) {
            long count = countOne(probe, parameters);
            total += count;
            // Every probe is reported, including the zeros: "no notifications" and "no probe exists
            // for notifications" are different facts and an operator reviewing a deletion needs the
            // first one.
            rows.add(new ImpactRow(probe, probe.table(), count));
        }
        return new Impact(List.copyOf(rows), total);
    }

    private long countOne(Probe probe, MapSqlParameterSource parameters) {
        String sql = switch (probe) {
            case SESSIONS -> SESSIONS;
            case NOTIFICATIONS -> NOTIFICATIONS;
            case PROJECT_MEMBERSHIPS -> MEMBERSHIPS;
            case MESSAGES -> MESSAGES;
            case WORKFLOW_RUNS -> WORKFLOW_RUNS;
            case ADMIN_AUDIT_EVENTS -> ADMIN_AUDIT;
            case SECURITY_EVENTS -> SECURITY;
            case LEGACY_AUDIT_LOGS -> LEGACY;
        };
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0L : value;
    }

    /** Kept so a future probe that needs no parameters has the constant in one place. */
    static MapSqlParameterSource noParameters() {
        return NONE;
    }
}