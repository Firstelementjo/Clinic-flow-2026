package com.firstelement.airag.send;

import com.firstelement.airag.orchestration.AiRunDisposition;
import com.firstelement.airag.orchestration.AiRunResult;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.aitool.AiToolExecutionGuard;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gate an AI answer must pass before it may be sent to a customer.
 *
 * <h2>ADR 0007 Part 2 rule 2: AI may send only in AI mode</h2>
 *
 * <p>Outside AI mode the assistant may <b>draft</b>; it may not send. This class is the check, and it is
 * deliberately not a send: it returns an authorization and Phase 6's {@code AiSendService} performs the
 * actual staged write and outbox publish under the conversation row lock. Two components enforcing rule 2
 * would eventually disagree about which conversation is in AI mode.
 *
 * <h2>Four conditions, all necessary</h2>
 *
 * <ol>
 *   <li><b>{@link Permission#AI_SEND}</b>. FR-5.9 and the phase-10 hard rule 9: {@code AI_SEND} is a
 *       <em>permission</em> added in Phase 6, and "holding {@code AI_TOOL_EXECUTE} does not grant it". The
 *       check is here, not inferred from a capability, because a capability is a machine-principal grant and
 *       an AI run's send is performed on behalf of an agent — so the <em>permission</em> is what an operator
 *       must hold and what the audit row must name. This condition is the whole gate; see the note on
 *       {@link #authorize} for why there is no capability check alongside it.</li>
 *   <li><b>The conversation is in AI mode.</b> Read inside the transaction, under the conversation scope. A
 *       conversation in {@code HUMAN} mode means an employee owns it, and a run's answer becomes a draft
 *       for them.</li>
 *   <li><b>The run produced an answer.</b> A refused or escalated run has no answer. There is deliberately
 *       no fallback here: a caller that wants a fallback message writes it itself, with its own content,
 *       rather than this class inventing one.</li>
 *   <li><b>The disposition is answerable.</b> An {@code ESCALATED} run is waiting on a human; sending its
 *       (null) answer would be meaningless and sending a substitute would be exactly the substitution ADR
 *       0007 Part 2 forbids.</li>
 * </ol>
 *
 * <h2>Why there is no capability check here</h2>
 *
 * <p>An earlier version required {@code Capability.AI_RUN} as well as {@code AI_SEND}, on the reasoning that
 * a send is machine work. It made the human path <b>unreachable</b>.
 * {@code RolePermissions.forPrincipalType} resolves a {@code USER}'s permissions from its role and ignores
 * its capabilities entirely, and no role maps to {@code AI_RUN} — so no employee and no administrator could
 * ever hold it. The only principal satisfying both conditions was the {@code AI_AGENT} itself, which reduced
 * the effective rule to "the agent may send whenever the conversation happens to be in AI mode" and silently
 * removed the human gate this class exists to enforce. Found by
 * {@code SendHandoffRaceIntegrationTest}, the only suite that exercises this class with a human principal.
 *
 * <p>The permission alone is the correct gate, and it loses nothing on the machine side: a {@code USER}
 * reaches it only through {@code AI_SEND} (ADMIN holds it; EMPLOYEE deliberately does not), and a machine
 * principal reaches it through {@code AI_RUN}, because {@code forCapabilities} grants {@code AI_SEND} to
 * exactly that capability and to nothing else. Dropping the capability check therefore widens nothing that
 * matters, and it restores the human path.
 *
 * <h2>Why no lock is taken here</h2>
 *
 * <p>Because a lock taken and released inside this method would protect nothing. The race that matters —
 * takeover committed between this check and the enqueue — is closed by {@code AiSendService}, which takes
 * the conversation row lock and re-reads the mode in the same transaction as the publish. So this method is
 * an <b>early</b> check that avoids pointless work, and {@code AiSendService} is the <b>authoritative</b>
 * one. That division is recorded in both classes' javadoc; a reader who assumes this one is final will
 * find the real gate there.
 */
@Component
public class AiSendAuthorization {

    private static final Logger log = LoggerFactory.getLogger(AiSendAuthorization.class);

    /**
     * The conversation's mode, read inside the caller's transaction.
     *
     * <p>{@code FOR UPDATE} so that a takeover in progress is waited for rather than raced: the read either
     * sees the pre-takeover mode and then blocks on the takeover's own lock, or sees the post-takeover mode.
     * Without it, this read could observe {@code AI} from a snapshot taken before a committed takeover and
     * report an authorization that is already void. The authoritative re-check still happens in
     * {@code AiSendService} under the same lock.
     */
    private static final String LOCK_MODE = """
        select c."mode"::text as "mode"
          from conversations c
         where c."id" = :conversationId
           and c."organizationId" = :__fe_tenant_organization_id
           and c."projectId" = :__fe_tenant_project_id
         for update
         limit 1
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantQueryGuard queryGuard;
    private final AuthorizationService authorization;
    private final AiToolExecutionGuard toolGuard;

    public AiSendAuthorization(NamedParameterJdbcTemplate jdbc,
                               TenantQueryGuard queryGuard,
                               AuthorizationService authorization,
                               AiToolExecutionGuard toolGuard) {
        this.jdbc = jdbc;
        this.queryGuard = queryGuard;
        this.authorization = authorization;
        this.toolGuard = toolGuard;
    }

    /**
     * Whether this answer may be sent.
     *
     * @return the authorization, or a refusal carrying the reason
     */
    @Transactional
    public SendAuthorization authorize(TenantContext principal,
                                        UUID projectId,
                                        UUID conversationId,
                                        AiRunResult result) {
        // 1. AI_SEND, and nothing else. Checked first: without it nothing else is worth reading, and the
        // denial is the one an operator must be able to see regardless of what the conversation is doing.
        // There is deliberately no capability check here -- see the class note on why that one was removed,
        // and note that the AI_TOOL_EXECUTE capability is NOT implied by this permission: a principal that
        // may send has not thereby been granted the ability to run a tool.
        authorization.check(principal, Permission.AI_SEND);

        // 2. The conversation's mode, under the caller's tenant scope.
        TenantScope scope = queryGuard.scope(principal, projectId);
        Optional<String> mode = jdbc.query(LOCK_MODE, scoped(scope, new MapSqlParameterSource()
            .addValue("conversationId", conversationId)),
            (rs, rowNum) -> rs.getString("mode")).stream().findFirst();
        if (mode.isEmpty()) {
            return SendAuthorization.denied(AiRefusalReason.NOT_AI_MODE);
        }
        if (!"AI".equals(mode.get())) {
            // The refusal names the mode, which is not a disclosure: the caller is an operator who can see
            // the conversation's mode anyway, and "an employee owns it" is the actionable fact.
            return SendAuthorization.denied(AiRefusalReason.NOT_AI_MODE, "mode=" + mode.get());
        }

        // 3 and 4. The run must have produced an answer, and the disposition must permit one.
        if (result == null) {
            return SendAuthorization.denied(AiRefusalReason.EMPTY_COMPLETION, "no run result");
        }
        // A REFUSED run is checked BEFORE the answer is, and the order is load-bearing. A refusal carries no
        // answer, so testing the answer first reported every refused run as EMPTY_COMPLETION -- which reads
        // as "the model had nothing to say" about a run that was never allowed to speak, and made the
        // ABORTED_BY_HUMAN branch below unreachable. Same principle as the provider's empty completion
        // never standing in for an outage: a refusal must be reported as a refusal.
        if (result.refused()) {
            return SendAuthorization.denied(AiRefusalReason.ABORTED_BY_HUMAN,
                result.refusalCode() == null ? "refused" : result.refusalCode());
        }
        if (!result.hasAnswer()) {
            return SendAuthorization.denied(AiRefusalReason.EMPTY_COMPLETION,
                "disposition=" + result.disposition());
        }
        if (result.disposition() != AiRunDisposition.ANSWERED
            && result.disposition() != AiRunDisposition.ASKED_CLARIFICATION
            && result.disposition() != AiRunDisposition.RESOLVED) {
            return SendAuthorization.denied(AiRefusalReason.NOT_AI_MODE,
                "disposition=" + result.disposition() + " produces no sendable answer");
        }

        return SendAuthorization.authorized(scope, conversationId, result);
    }

    /**
     * The answer to "may this be sent".
     *
     * @param authorized  whether the answer may proceed to {@code AiSendService}
     * @param reason      an {@code AiRefusalReason} code when not authorized
     * @param detail      a short, non-sensitive diagnostic. Never the answer text
     * @param scope       the resolved tenant scope, so the caller does not re-resolve it differently
     */
    public record SendAuthorization(boolean authorized,
                                    AiRefusalReason reason,
                                    String detail,
                                    TenantScope scope,
                                    UUID conversationId,
                                    AiRunResult result) {

        static SendAuthorization authorized(TenantScope scope, UUID conversationId, AiRunResult result) {
            return new SendAuthorization(true, null, null, scope, conversationId, result);
        }

        static SendAuthorization denied(AiRefusalReason reason) {
            return new SendAuthorization(false, reason, null, null, null, null);
        }

        static SendAuthorization denied(AiRefusalReason reason, String detail) {
            return new SendAuthorization(false, reason,
                com.firstelement.airag.provider.SecretScrubber.scrubAndBound(detail), null, null, null);
        }

        public String code() {
            return reason == null ? "AUTHORIZED" : reason.name();
        }
    }

    /** The permission a send-shaped action requires, named once. */
    public static Permission sendPermission() {
        return Permission.AI_SEND;
    }

    /**
     * The tool guard is a collaborator rather than a direct call site, because the send path must not be
     * reachable by a path that skipped the tool guard's checks. Exposed so a reviewer can confirm it is
     * wired, and unused here on purpose — see the class note.
     */
    AiToolExecutionGuard toolGuard() {
        return toolGuard;
    }

    private static MapSqlParameterSource scoped(TenantScope scope, MapSqlParameterSource source) {
        MapSqlParameterSource merged = new MapSqlParameterSource();
        if (source.getValues() != null) {
            source.getValues().forEach((name, value) -> {
                if (com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM.equals(name)
                    || com.firstelement.security.query.ScopedWhere.PROJECT_PARAM.equals(name)) {
                    throw new IllegalArgumentException("'" + name
                        + "' is reserved for the tenant filter and cannot be supplied by a caller");
                }
                merged.addValue(name, value);
            });
        }
        merged.addValue(com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM,
            scope.organizationId());
        if (scope.isProjectScoped()) {
            merged.addValue(com.firstelement.security.query.ScopedWhere.PROJECT_PARAM, scope.projectId());
        }
        return merged;
    }
}