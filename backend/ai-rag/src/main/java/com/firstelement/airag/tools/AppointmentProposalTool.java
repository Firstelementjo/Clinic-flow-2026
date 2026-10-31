package com.firstelement.airag.tools;

import com.firstelement.security.Capability;
import com.firstelement.security.aitool.AiToolDescriptor;
import com.firstelement.security.permission.Permission;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code appointment.propose} — a CONSEQUENTIAL tool that can only ever <em>propose</em>.
 *
 * <h2>This tool cannot book anything, and it does not even check availability</h2>
 *
 * <p>{@link #perform} throws unconditionally. That is the design, not an oversight. ADR 0007 Part 2: a
 * consequential action requires human approval and "the model may only <em>request</em> the action". A body
 * that could perform would be one refactor away from executing, and the schema's
 * {@code ai_tool_executions_allow_consequential_needs_approval_check} would then be the only barrier on an
 * external side effect. So the barrier is in the code and the constraint is the second line.
 *
 * <p>It also reads no availability table, and that is a deliberate consequence of the same rule rather
 * than an omission. Whether a slot exists is a <b>question about the calendar</b>, and answering it means
 * reading the booking system in a scope the model chose the doctor in. Publishing a proposal with a
 * {@code slotFound} claim would either require that read — turning a proposal into a reconnaissance tool
 * over another practitioner's schedule — or require inventing the claim. Neither is acceptable, so the
 * proposal reports only what the model asked for, and the human checks the calendar. An approver reading
 * "Tuesday 10:00, 30 minutes, doctor X" has exactly what they need to consent.
 *
 * <h2>The doctor id is a body argument; the project is not</h2>
 *
 * <p>{@code projectId} is in {@link com.firstelement.security.aitool.AiToolDescriptor#FORBIDDEN_ARGUMENTS}
 * by construction, so it never reaches this body: the project comes from the context's {@code TenantScope},
 * resolved by {@code TenantQueryGuard}. A model that sends {@code {"projectId": "<other>"}} is rejected
 * outright and recorded — the acceptance gate's "scope-override arguments rejected".
 *
 * <h2>Why {@code requiredPermission} is a literal and not an enum constant</h2>
 *
 * <p>{@code Permission} lives in {@code :backend:tenant-security}, outside this phase's ownership globs, and
 * it has no appointment permission. ADR 0010 §2 forbids borrowing another domain's permission name for
 * this one — booking an appointment is not a workflow, a conversation or an SLA, and naming the required
 * permission {@code workflow:manage} would make the audit row assert something untrue. So the value is
 * declared as the literal the domain needs, {@value #REQUIRED_PERMISSION}, and the missing enum constant is
 * reported to the orchestrator as a hook rather than worked around by misnaming it. Until it exists, the
 * executor treats this tool's permission requirement as <b>not satisfiable by a human role</b>, which means
 * only an administrator can enable the tool — the fail-closed direction.
 */
@Component
public class AppointmentProposalTool implements AiToolBody {

    public static final String NAME = "appointment.propose";

    /** The permission this capability should be named after. See the class note. */
    public static final String REQUIRED_PERMISSION = "appointment:propose";

    /** The field allow-list, which is also the projection the executor applies to the result. */
    public static final Set<String> OUTPUT_FIELDS =
        Set.of("doctorId", "proposedDay", "proposedStartTime", "durationMinutes", "requiresApproval");

    private static final int MAX_DAY_LENGTH = 10;
    private static final int MAX_NOTE_LENGTH = 280;

    /**
     * The declaration.
     *
     * <p>CONSEQUENTIAL, MEDIUM, {@code consequential = true}. MEDIUM rather than HIGH because nothing is
     * written and nothing leaves the system: the risk is an <em>approval</em> that a human grants, and the
     * booking itself is a human or Phase-12 action. The schema forbids enabling a HIGH or CRITICAL tool
     * with an empty field allow-list, and {@link #OUTPUT_FIELDS} is non-empty.
     */
    public static AiToolDefinition definition() {
        return new AiToolDefinition(
            NAME,
            "اقتراح موعد (يتطلب موافقة بشرية)",
            Set.of("doctorId", "day", "startMinute", "durationMinutes", "note"),
            Map.of(
                "doctorId", AiToolDefinition.ArgumentSpec.requiredUuid("doctorId"),
                "day", AiToolDefinition.ArgumentSpec.requiredString("day", MAX_DAY_LENGTH),
                "startMinute", AiToolDefinition.ArgumentSpec.optionalInteger("startMinute", 0, 1439),
                "durationMinutes", AiToolDefinition.ArgumentSpec.optionalInteger("durationMinutes", 5, 480),
                "note", AiToolDefinition.ArgumentSpec.optionalString("note", MAX_NOTE_LENGTH)),
            OUTPUT_FIELDS,
            Capability.AI_TOOL_EXECUTE,
            REQUIRED_PERMISSION,
            AiToolDefinition.AiRiskLevel.MEDIUM,
            true,
            true,
            true,
            AiToolDefinition.AiActionClass.CONSEQUENTIAL);
    }

    /**
     * Never performs.
     *
     * @throws UnsupportedOperationException always. The message names the policy so a later reader does
     *         not mistake this for an unimplemented feature and "helpfully" finish it.
     */
    @Override
    public Map<String, Object> perform(AiToolContext context) {
        throw new UnsupportedOperationException(
            "appointment.propose is a CONSEQUENTIAL action and may only be REQUESTED. Executing it from a "
                + "model run is forbidden by ADR 0007 Part 2; the executor records the request as "
                + "REQUIRE_APPROVAL and creates no appointment. A human approves; a human (or Phase 12) books.");
    }

    /**
     * The proposal.
     *
     * <p>Pure computation. No statement, no write, no outbox intent, no message. The approval row is
     * written by the executor, not here, so this body cannot create an approval without going through the
     * decision that produced it.
     */
    @Override
    public Map<String, Object> dryRun(AiToolContext context) {
        UUID doctorId = context.requireUuid("doctorId");
        LocalDate day = parseDay(context.requireString("day"));
        Integer startMinute = context.requireInteger("startMinute");
        Integer duration = context.requireInteger("durationMinutes");

        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("doctorId", doctorId.toString());
        proposal.put("proposedDay", day.toString());
        proposal.put("proposedStartTime", minutes(startMinute == null ? 0 : startMinute));
        proposal.put("durationMinutes", duration == null ? 30 : duration);
        proposal.put("requiresApproval", true);
        // Projected through the declared allow-list, so a future edit that adds a field to this map
        // without declaring it produces nothing rather than leaking into ai_tool_executions.
        return project(proposal);
    }

    /**
     * Parses an ISO date.
     *
     * <p>Refuses rather than guessing. A tool that accepted "next Tuesday" and picked a date would produce
     * an approval request for a time nobody chose, and the person approving it could not tell which.
     */
    private static LocalDate parseDay(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                "appointment.propose requires an ISO-8601 'day' (YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                "appointment.propose requires an ISO-8601 'day' (YYYY-MM-DD); refusing to interpret '"
                    + com.firstelement.airag.provider.SecretScrubber.scrubAndBound(raw) + "'", e);
        }
    }

    private static String minutes(int minuteOfDay) {
        int hours = Math.min(23, minuteOfDay / 60);
        int mins = minuteOfDay % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, mins);
    }

    /** Keeps only the declared output fields. */
    private static Map<String, Object> project(Map<String, Object> result) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String field : OUTPUT_FIELDS) {
            if (result.containsKey(field)) {
                projected.put(field, result.get(field));
            }
        }
        return projected;
    }

    /**
     * The descriptor this tool answers to at the guard boundary.
     *
     * <p>{@code consequential = true} becomes {@code highRisk = true}, which is what makes
     * {@link com.firstelement.security.aitool.AiToolExecutionGuard} demand {@code humanApproved} before the
     * body may run at all — including before {@link #dryRun} is reached, which is the correct order: an
     * unapproved consequential action is not described either.
     */
    public AiToolDescriptor descriptor(boolean registryEnabled) {
        return definition().descriptorFor(registryEnabled);
    }

    /** The capability a human operator would need; the tool is enabled by {@code AI_AGENT_CONFIGURE}. */
    public static Permission enablingPermission() {
        return Permission.AI_AGENT_CONFIGURE;
    }
}