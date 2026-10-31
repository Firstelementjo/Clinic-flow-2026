package com.firstelement.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces clinic suspension across all clinic-staff write operations.
 *
 * <p>Specification compliance:
 * - Suspended/Expired = clinic staff blocked from create/edit (read-only) or shown renewal notice.
 * - Suspension NEVER deletes data. Retention follows DEC-007 (10 years from last procedure).
 * - Suspended clinic cannot write; data intact.
 * - Read requests (GET) remain available in read-only mode so existing medical records are preserved.
 * - Platform operators are exempt and retain full oversight and reactivation capability.
 */
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 2)
public class SuspendedClinicEnforcementFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SuspendedClinicEnforcementFilter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // Safe methods (GET, HEAD, OPTIONS) and logout are always permitted
        if (!WRITE_METHODS.contains(method) || uri.endsWith("/auth/logout")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Platform endpoints have their own security chain
        if (uri.startsWith("/api/v1/platform")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check if an authenticated tenant context exists
        TenantContext context = TenantContextHolder.current().orElse(null);
        if (context == null || context.isPlatformAdmin()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Determine if any associated project or the organization is suspended
        UUID orgId = context.organizationId();
        String projectIdCandidate = request.getHeader("X-Project-Id");
        if (projectIdCandidate == null || projectIdCandidate.isBlank()) {
            projectIdCandidate = request.getParameter("projectId");
        }

        if (orgId != null && isClinicSuspended(orgId, projectIdCandidate)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");

            Map<String, Object> errorPayload = Map.of(
                "error", "CLINIC_SUSPENDED",
                "message", "This clinic is currently suspended. Write operations are disabled. Medical data is retained according to DEC-007."
            );
            response.getWriter().write(objectMapper.writeValueAsString(errorPayload));
            response.getWriter().flush();
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isClinicSuspended(UUID orgId, String projectIdHeader) {
        if (projectIdHeader != null && !projectIdHeader.isBlank()) {
            try {
                UUID projectId = UUID.fromString(projectIdHeader.trim());
                Integer count = jdbc.queryForObject("""
                    select count(*)
                      from "public"."projects"
                     where "id" = :projId
                       and "organizationId" = :orgId
                       and "status" in ('SUSPENDED', 'EXPIRED')
                    """,
                    new MapSqlParameterSource()
                        .addValue("projId", projectId)
                        .addValue("orgId", orgId),
                    Integer.class);
                if (count != null && count > 0) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid UUID header and fall back to organization check
            }
        }

        Integer orgSuspended = jdbc.queryForObject("""
            select count(*)
              from "public"."projects"
             where "organizationId" = :orgId
               and "status" in ('SUSPENDED', 'EXPIRED')
            """,
            new MapSqlParameterSource("orgId", orgId),
            Integer.class);

        return orgSuspended != null && orgSuspended > 0;
    }
}
