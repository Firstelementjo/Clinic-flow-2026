package com.firstelement.airag.tools;

import com.firstelement.security.Capability;
import com.firstelement.security.query.ScopedWhere;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code conversation.lookup} — the only read-only tool, and the proof that a read is scoped like any
 * other read.
 *
 * <h2>The statement</h2>
 *
 * <p>Hand-written, with an explicit column list, and carrying {@link ScopedWhere}'s tenant predicate.
 * It returns <b>counts and status</b> only: no message bodies, no customer names, no phone numbers. The
 * {@link #fieldAllowList()} on the definition names the six scalars it may return, so even a future edit to
 * this statement cannot widen the tool's output without also widening the declared contract that
 * {@code ai_tool_registry."fieldAllowList"} stores and the guard checks.
 *
 * <h2>Why the conversation id is validated, not trusted</h2>
 *
 * <p>The body issues its statement through the context's {@code TenantScope}, so a conversation id in
 * another tenant produces <b>no row</b> rather than another tenant's counts. That is a refusal by
 * emptiness — which is correct here because the tool is answerable, not consequential: the answer is "there
 * is no such conversation in your scope", which is true and discloses nothing. A consequential tool cannot
 * use emptiness as a refusal, which is why those tools check existence explicitly (see
 * {@link AppointmentProposalTool}).
 *
 * <h2>Action class and risk</h2>
 *
 * <p>ANSWERABLE and LOW. ADR 0007 Part 2 permits an answerable action in AI mode with citations, and this
 * tool cites nothing — it reports counts the assistant may or may not use. The schema forbids an ANSWERABLE
 * action from carrying an approval id, which is asserted by the executor's own decision, not left to the
 * reader.
 */
@Component
public class ConversationLookupTool implements AiToolBody {

    /** The name, matching {@code ai_tool_registry."toolName"} and the agent version's enabledTools. */
    public static final String NAME = "conversation.lookup";

    /** The statement. Fixed columns, fixed table, tenant predicate inside the WHERE clause. */
    private static final String LOOKUP = """
        select c."status"::text      as "status",
               c."mode"::text        as "mode",
               c."priority"::text    as "priority",
               c."messageCount"      as "messageCount",
               c."firstResponseAt"   as "firstResponseAt",
               c."lastMessageAt"     as "lastMessageAt"
          from conversations c
         where c."id" = :conversationId
           and c."organizationId" = :__fe_tenant_organization_id
           and c."projectId" = :__fe_tenant_project_id
         limit 1
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationLookupTool(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The declaration.
     *
     * <p>{@code projectScoped = true}: the tool reads a conversation, and a conversation belongs to a
     * project. {@code requiredCapability = KNOWLEDGE_RETRIEVE}, which
     * {@code Capability.mayBeHeldBy} restricts to WORKER and AI_AGENT principals — so a human principal
     * cannot invoke this tool directly even with the matching role permission. That is the separation
     * between "the operator may read conversations" ({@code CONVERSATION_READ}) and "the <em>model</em> may
     * ask for one" ({@code KNOWLEDGE_RETRIEVE}).
     */
    public static AiToolDefinition definition() {
        return new AiToolDefinition(
            NAME,
            "قراءة حالة المحادثة وعدد الرسائل (بلا محتوى)",
            Set.of("conversationId"),
            Map.of("conversationId", AiToolDefinition.ArgumentSpec.requiredUuid("conversationId")),
            // "found" is in the allow-list because it is the tool's whole answer: a conversation outside the scope is
            // reported as found=false rather than as an error. Excluding it would make the projection drop
            // the one field that distinguishes "no such conversation in your scope" from a tool that
            // failed, which is precisely the distinction a refusal must not blur.
            Set.of("found", "status", "mode", "priority", "messageCount", "firstResponseAt", "lastMessageAt"),
            Capability.KNOWLEDGE_RETRIEVE,
            com.firstelement.security.permission.Permission.CONVERSATION_READ.value(),
            AiToolDefinition.AiRiskLevel.LOW,
            false,
            true,
            true,
            AiToolDefinition.AiActionClass.ANSWERABLE);
    }

    @Override
    public Map<String, Object> perform(AiToolContext context) {
        UUID conversationId = context.requireUuid("conversationId");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("conversationId", conversationId);
        // The tenant values are written after the caller's, exactly as ScopedWhere does, so no caller
        // parameter can shadow the filter.
        parameters.addValue(ScopedWhere.ORGANIZATION_PARAM, context.scope().organizationId());
        if (context.scope().isProjectScoped()) {
            parameters.addValue(ScopedWhere.PROJECT_PARAM, context.scope().projectId());
        }

        List<Map<String, Object>> rows = jdbc.queryForList(LOOKUP, parameters);
        if (rows.isEmpty()) {
            // Not an error and not a disclosure: the conversation is not in this scope, which is the same
            // answer as "it does not exist" and is what a caller sees for a random UUID too.
            Map<String, Object> absent = new LinkedHashMap<>();
            absent.put("found", false);
            absent.put("messageCount", 0);
            return absent;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        // Projected through the declared field allow-list, so a column added to the statement above does
        // not silently become tool output.
        for (String field : definition().fieldAllowList()) {
            if (row.containsKey(field)) {
                result.put(field, row.get(field));
            }
        }
        return result;
    }

    /**
     * The dry run, which for a read is the read.
     *
     * <p>Declared and implemented so the executor's uniform path is exercised: a read has no effect to
     * withhold, and saying so is better than throwing and forcing every caller to special-case this tool.
     */
    @Override
    public Map<String, Object> dryRun(AiToolContext context) {
        return perform(context);
    }
}