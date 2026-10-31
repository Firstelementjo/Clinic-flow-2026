package com.firstelement.admin.web;

import com.firstelement.admin.maintenance.MaintenanceCommand;
import com.firstelement.admin.maintenance.MaintenanceCommandService;
import com.firstelement.admin.maintenance.ReplaySafetyPolicy;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/maintenance} — the maintenance command catalogue.
 *
 * <h2>Why the catalogue is a route at all</h2>
 *
 * <p>An operator needs to know what the platform <em>can</em> do before deciding what it should do,
 * and — more importantly — needs to see the commands that are <b>off</b>. "There is no purge here"
 * and "there is a purge here and it is disabled by policy, and here is the policy" are different
 * answers, and only the second one lets somebody raise the ADR that would change it.
 *
 * <h2>There is no command route here</h2>
 *
 * <p>This class exposes no {@code POST}. Every command lives on its own typed route with its own
 * {@code @RequirePermission} on a purpose-built service method, because a route that took a command
 * name from a request would put every command behind a single permission decision — which is the
 * shape that produces "one route, one permission, seven capabilities".
 */
@RestController
@RequestMapping("/api/v1/admin/maintenance")
public class AdminMaintenanceController {

    private final MaintenanceCommandService commands;

    public AdminMaintenanceController(MaintenanceCommandService commands) {
        this.commands = commands;
    }

    /**
     * {@code GET /api/v1/admin/maintenance/commands}
     *
     * <p>Held by {@code SYSTEM_HEALTH_READ}: the catalogue is a description of what an operator may
     * do, and the {@code callerHoldsPermission} flag per row is computed by the same
     * {@code AuthorizationService} the commands themselves use, so the list cannot drift from the
     * enforcement.
     */
    @GetMapping("/commands")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public Map<String, Object> commands() {
        TenantContext caller = TenantContextHolder.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", caller.organizationId());
        payload.put("commands", commands.catalogue(caller));
        payload.put("replaySafety", ReplaySafetyPolicy.catalogue());
        payload.put("notes", List.of(
            "Every command here is allow-listed and typed. There is no endpoint that accepts SQL, a "
                + "table name, a column name or a free-form row.",
            "A command marked destructive is the only kind that would remove customer data, and "
                + "both of those ship disabled.",
            "Every command writes an audit record whether it succeeds or is refused.",
            "A command that names a permission is checked at this HTTP edge and again in the "
                + "service; the service is the boundary."));
        return payload;
    }

    /** The disabled commands on their own, so a UI can render a clear "not available" panel. */
    @GetMapping("/commands/disabled")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public Map<String, Object> disabledCommands() {
        TenantContext caller = TenantContextHolder.get();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaintenanceCommand command : MaintenanceCommand.all()) {
            if (command.enabled()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("command", command.wireValue());
            row.put("requiredPermission", command.requiredPermission().value());
            row.put("destructive", command.destructive());
            row.put("description", command.description());
            row.put("reason", "ADR-0009 section 4: ships disabled until every table has a declared "
                + "retention class and the purge/deletion job exists as a durable batched job.");
            rows.add(row);
        }
        return Map.of("commands", rows);
    }
}