package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.firstelement.automation.service.AiSendService;
import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * A customer-visible write must be authorized by a write permission.
 *
 * <h2>The defect, as an exclusion rather than an inclusion</h2>
 *
 * <p>{@link AiSendService#stageAiReply} and {@link AiSendService#confirmAiSend} both guarded a
 * customer-visible write with {@link Permission#AI_RUN_READ}. ADR 0010 §2 says a permission is named
 * after what it authorizes, and {@code AuthorizationServiceTest} records the history: Phase 6
 * escalated a send that reused the read-only {@code AI_RUN_READ}, and that escalation is precisely why
 * {@link Permission#AI_SEND} exists. The send was simply never moved onto it.
 *
 * <p>Proving "a sender is allowed" would pass either way, because {@code AI_RUN_READ} is granted to
 * {@link Capability#AI_RUN} too. The falsifiable claim is the other one:
 * {@code Capability.KNOWLEDGE_RETRIEVE} maps to
 * {@code EnumSet.of(KNOWLEDGE_READ, AI_RUN_READ)} and <b>not</b> to {@code AI_SEND}, so a
 * read-oriented retrieval principal was passing the gate on the path that writes an AI-authored
 * message into a customer's conversation. Every negative below is therefore built on a
 * retrieval-only principal, against a real conversation, a real run and a real database.
 *
 * <h2>What is asserted about a refusal</h2>
 *
 * <p>Not that something was thrown. For each phase: the message row was not written, no delivery intent
 * reached the outbox, no action was appended to the run's history, and the denial is on the audit stream
 * naming {@code ai-send}. A refusal that left a staged row or a published intent behind would be a worse
 * failure than the missing permission.
 *
 * <h2>Why the audit assertion can name the permission</h2>
 *
 * <p>{@code AuthorizationService#denyRole} publishes {@code ACCESS_DENIED} with the missing permission's
 * wire value as the resource id and in the metadata, and the message the caller receives is a bare
 * {@code "Access denied"}. The naming lives in the audit record, which is where it belongs — see
 * {@code SendHandoffRaceIntegrationTest > who may send}, which asserts the same grant-table facts from
 * the other side of the module edge.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class AiSendPermissionIntegrationTest extends WorkflowIntegrationTestBase {

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private AiSendService aiSend;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext sender;
    private TenantContext retriever;

    @BeforeEach
    void reset() {
        fixture.reset();
        sender = PrincipalScope.aiAgent();
        retriever = PrincipalScope.knowledgeRetriever();
    }

    // ------------------------------------------------------------------
    // the grant table, read directly
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the send permission is granted to a send capability and nothing read-only")
    class GrantTable {

        @Test
        @DisplayName("AI_SEND appears in exactly one capability set, and that set is AI_RUN")
        void aiSendIsGrantedToExactlyAiRun() {
            List<Capability> granting = Arrays.stream(Capability.values())
                .filter(capability -> RolePermissions.forCapabilities(EnumSet.of(capability))
                    .contains(Permission.AI_SEND))
                .toList();

            assertThat(granting)
                .as("one capability grants AI_SEND, and widening the table must be a visible edit here")
                .containsExactly(Capability.AI_RUN);
            assertThat(RolePermissions.forCapabilities(EnumSet.of(Capability.AI_RUN)))
                .contains(Permission.AI_SEND);
        }

        @Test
        @DisplayName("no read-only capability grants AI_SEND")
        void noReadOnlyCapabilityGrantsTheSend() {
            // The name is the contract: a capability whose name ends in READ retrieves or observes. None
            // of them may be able to write an AI-authored message into a customer's conversation.
            for (Capability capability : Capability.values()) {
                if (!capability.name().endsWith("_READ")) {
                    continue;
                }
                assertThat(RolePermissions.forCapabilities(EnumSet.of(capability)))
                    .as("%s is a read capability and must not grant %s", capability, Permission.AI_SEND)
                    .doesNotContain(Permission.AI_SEND);
            }
            // Named explicitly as well, because the loop above would pass on an empty match set.
            assertThat(RolePermissions.forCapabilities(EnumSet.of(Capability.KNOWLEDGE_RETRIEVE)))
                .as("and this is the exact pair that made the defect reachable")
                .contains(Permission.AI_RUN_READ)
                .doesNotContain(Permission.AI_SEND);
        }

        @Test
        @DisplayName("the retrieval principal is read-oriented in the taxonomy, not just in the table")
        void theRetrievalPrincipalIsGenuinelyReadOnly() {
            assertThat(retriever.isMachine()).isTrue();
            assertThat(retriever.isUser()).isFalse();
            assertThat(retriever.hasCapability(Capability.KNOWLEDGE_RETRIEVE)).isTrue();
            assertThat(retriever.hasCapability(Capability.AI_RUN))
                .as("the two principals must differ, or the refusals below prove nothing")
                .isFalse();
            assertThat(retriever.auditActorLabel())
                .as("a non-disclosing machine label: the principal type, and a namespaced id rather "
                    + "than a name that could be logged back into a prompt")
                .startsWith(PrincipalType.AI_AGENT.value() + ":")
                .doesNotContain("knowledge-retriever");
        }

        @Test
        @DisplayName("AI_SEND is an administrator permission, so an employee still cannot send")
        void employeeStillCannotSend() {
            assertThat(RolePermissions.forRole(com.firstelement.security.UserRole.EMPLOYEE))
                .contains(Permission.AI_RUN_READ)
                .doesNotContain(Permission.AI_SEND);
            assertThat(RolePermissions.forRole(com.firstelement.security.UserRole.ADMIN))
                .as("and the human administrator reaches the same gate the agent does")
                .contains(Permission.AI_SEND);
        }
    }

    // ------------------------------------------------------------------
    // the legitimate sender
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a principal holding AI_RUN can still send on both phases")
    class LegitimateSender {

        @Test
        @DisplayName("AI_RUN stages a reply, and the staged row exists")
        void aiRunCanStage() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);

            AiSendService.SendOutcome staged = PrincipalScope.call(sender, () ->
                aiSend.stageAiReply(sender, runId, "ساعات العمل من الأحد إلى الخميس.",
                    "workflow:ai.send"));

            assertThat(staged.status()).isEqualTo(AiSendService.SendOutcome.Status.STAGED);
            assertThat(staged.refused()).isFalse();
            assertThat(staged.messageId()).isNotNull();
            assertThat(pendingAiMessages(conversationId)).isEqualTo(1);
        }

        @Test
        @DisplayName("AI_RUN confirms the send, and the delivery intent is published exactly once")
        void aiRunCanConfirm() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);
            AiSendService.SendOutcome staged = PrincipalScope.call(sender, () ->
                aiSend.stageAiReply(sender, runId, "تم استلام طلبك.", "workflow:ai.send"));

            AiSendService.SendOutcome published = PrincipalScope.call(sender, () ->
                aiSend.confirmAiSend(sender, runId, staged.messageId()));

            assertThat(published.status()).isEqualTo(AiSendService.SendOutcome.Status.PUBLISHED);
            assertThat(outboundIntentsFor(staged.messageId())).isEqualTo(1);
        }

        @Test
        @DisplayName("the ADMIN role reaches both phases too, so no human path is locked out")
        void adminRoleStillReachesBothPhases() {
            TenantContext admin = PrincipalScope.adminA();
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);

            AiSendService.SendOutcome staged = PrincipalScope.call(admin, () ->
                aiSend.stageAiReply(admin, runId, "Checking for you now.", "workflow:ai.send"));
            AiSendService.SendOutcome published = PrincipalScope.call(admin, () ->
                aiSend.confirmAiSend(admin, runId, staged.messageId()));

            assertThat(staged.status()).isEqualTo(AiSendService.SendOutcome.Status.STAGED);
            assertThat(published.status()).isEqualTo(AiSendService.SendOutcome.Status.PUBLISHED);
            assertThat(outboundIntentsFor(staged.messageId())).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // the defect: phase 1
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("phase 1 refuses a principal with AI_RUN_READ but not AI_SEND")
    class StageRefusal {

        @Test
        @DisplayName("staging is denied, and the denial names the missing permission")
        void stageIsDeniedNamingAiSend() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);

            Throwable thrown = catchThrowable(() -> PrincipalScope.call(retriever, () ->
                aiSend.stageAiReply(retriever, runId, "إجابة مُولَّدة", "workflow:ai.send")));

            assertThat(thrown)
                .as("a write permission is required for a write, so the gate refuses rather than "
                    + "accepting the read the principal does hold")
                .isInstanceOf(AccessDeniedException.class);
            // AccessDeniedException carries a deliberately bare message, so the naming is asserted where
            // it is published rather than guessed at.
            assertThat(deniedPermissionsInAudit())
                .as("the refusal names the permission that was missing")
                .containsExactly(Permission.AI_SEND);
        }

        @Test
        @DisplayName("the refusal writes no message row, so nothing is left to send later")
        void stageWritesNoMessageRow() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);

            catchThrowable(() -> PrincipalScope.call(retriever, () ->
                aiSend.stageAiReply(retriever, runId, "إجابة مُولَّدة", "workflow:ai.send")));

            assertThat(pendingAiMessages(conversationId)).isZero();
            assertThat(aiMessages(conversationId))
                .as("not merely unsendable: no AI-authored row exists at all, so a later "
                    + "dispatcher, retry or manual confirm has nothing to pick up")
                .isZero();
        }

        @Test
        @DisplayName("the refusal publishes no delivery intent and appends no run action")
        void stagePublishesNothing() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);

            catchThrowable(() -> PrincipalScope.call(retriever, () ->
                aiSend.stageAiReply(retriever, runId, "إجابة مُولَّدة", "workflow:ai.send")));

            assertThat(totalOutboundIntents())
                .as("the outbox is empty, so the refusal cannot become a delivery by another route")
                .isZero();
            assertThat(runActionCount(runId))
                .as("and the run's history records no staged action, because nothing was staged")
                .isZero();
        }
    }

    // ------------------------------------------------------------------
    // the defect: phase 2
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("phase 2 refuses the same principal, after a legitimate stage")
    class ConfirmRefusal {

        @Test
        @DisplayName("confirming is denied even though the principal could not have staged it itself")
        void confirmIsDeniedNamingAiSend() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);
            // Staged by a principal that legitimately holds the permission, so the only thing standing
            // between this call and a customer-visible delivery is the check under test.
            AiSendService.SendOutcome staged = PrincipalScope.call(sender, () ->
                aiSend.stageAiReply(sender, runId, "تم استلام طلبك.", "workflow:ai.send"));

            Throwable thrown = catchThrowable(() -> PrincipalScope.call(retriever, () ->
                aiSend.confirmAiSend(retriever, runId, staged.messageId())));

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            assertThat(deniedPermissionsInAudit()).containsExactly(Permission.AI_SEND);
        }

        @Test
        @DisplayName("the refused confirm publishes no intent, and the staged row stays PENDING")
        void confirmPublishesNothing() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);
            AiSendService.SendOutcome staged = PrincipalScope.call(sender, () ->
                aiSend.stageAiReply(sender, runId, "تم استلام طلبك.", "workflow:ai.send"));

            catchThrowable(() -> PrincipalScope.call(retriever, () ->
                aiSend.confirmAiSend(retriever, runId, staged.messageId())));

            assertThat(outboundIntentsFor(staged.messageId())).isZero();
            assertThat(totalOutboundIntents()).isZero();
            // Still PENDING, not FAILED: the staged row is untouched. It is cancellable by a takeover
            // and it is not deliverable without an intent, and the refusal did not pretend otherwise by
            // marking a message this principal never legitimately sent.
            assertThat(messageStatus(staged.messageId())).isEqualTo("PENDING");
            assertThat(runActionCount(runId))
                .as("only the legitimate stage is on the history; the refusal appended nothing")
                .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // the audit
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the refusal is audited like any other authorization failure")
    class Audited {

        @Test
        @DisplayName("the denial is on the audit stream, and it survives the refused transaction")
        void denialIsAudited() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);

            int before = deniedPermissionRows();

            catchThrowable(() -> PrincipalScope.call(retriever, () ->
                aiSend.stageAiReply(retriever, runId, "إجابة مُولَّدة", "workflow:ai.send")));

            assertThat(deniedPermissionRows())
                .as("an audit trail that rolls back with the operation it describes is not a trail, so "
                    + "the row is written REQUIRES_NEW and must be here afterwards")
                .isEqualTo(before + 1);
            assertThat(denialActors())
                .as("the actor is the machine principal, not a user, so the stream stays readable")
                .contains(PrincipalType.AI_AGENT.value());
            assertThat(denialTenants())
                .as("and the event is attributed to the principal's own tenant, never a claimed one")
                .containsOnly(WorkflowFixture.ORG_A);
        }

        @Test
        @DisplayName("the denial carries no reply body, so customer content cannot reach the stream")
        void denialCarriesNoContent() {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);
            String body = "المريض 1012345678، المبلغ 450 ريال";

            catchThrowable(() -> PrincipalScope.call(retriever, () ->
                aiSend.stageAiReply(retriever, runId, body, "workflow:ai.send")));

            // Asserted over the whole concatenated metadata rather than over one row's fields: the point
            // is that the reply text -- which is customer content -- reaches the audit stream by no route.
            assertThat(String.join(" ", denialMetadata()))
                .as("the audit answers who did what to which resource, never what was said")
                .doesNotContain("1012345678")
                .doesNotContain(body)
                .doesNotContain("450")
                .contains(Permission.AI_SEND.value());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID aiConversation() {
        return fixture.conversation(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A,
            WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
    }

    /**
     * A running AI run whose {@code capabilityScope} names the send capability.
     *
     * <p>Deliberately independent of the actor: the scope is copied onto the run, so the same run is
     * sendable for {@link Capability#AI_RUN} and unsendable for {@link Capability#KNOWLEDGE_RETRIEVE},
     * and the only difference between the refused and the allowed call is the permission.
     *
     * @return the run id
     */
    private UUID runningAiRun(UUID conversationId) {
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
            insert into workflow_definitions
                ("id", "organizationId", "projectId", "key", "name", "triggerType", "priority",
                 "isEnabled", "latestVersionNumber", "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'AI send permission fixture', 'MESSAGE_SCORED', 10,
                    true, 1, :now, :now)
            """, new MapSqlParameterSource().addValue("id", definitionId)
                .addValue("org", WorkflowFixture.ORG_A).addValue("project", WorkflowFixture.PROJECT_A)
                .addValue("key", "ai-send-permission-" + definitionId)
                .addValue("now", WorkflowFixture.SEED_AT));
        jdbc.update("""
            insert into workflow_versions
                ("id", "organizationId", "projectId", "workflowDefinitionId", "versionNumber",
                 "state", "stateMachine", "triggerConfig", "capabilityScope", "contentHash",
                 "activatedAt", "activatedByUserId", "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionId, 1, cast('ACTIVE' as "WorkflowVersionState"),
                    cast(:machine as jsonb), cast('{}' as jsonb), cast(:scope as jsonb), 'fixture',
                    :now, :activatedBy, :now, :now)
            """, new MapSqlParameterSource().addValue("id", versionId)
                .addValue("org", WorkflowFixture.ORG_A).addValue("project", WorkflowFixture.PROJECT_A)
                .addValue("definitionId", definitionId)
                .addValue("machine", json(WorkflowDocuments.sendAiReply("workflow:ai.send")))
                .addValue("scope", "[\"workflow:ai.send\"]")
                .addValue("now", WorkflowFixture.SEED_AT)
                .addValue("activatedBy", WorkflowFixture.ADMIN_A));

        UUID runId = UUID.randomUUID();
        jdbc.update("""
            insert into workflow_runs
                ("id", "organizationId", "projectId", "workflowDefinitionId", "workflowVersionId",
                 "conversationId", "triggerInstanceId", "status", "principalUserId",
                 "capabilityScope", "loopDepth", "maxLoopDepth", "startedAt", "createdAt", "updatedAt",
                 "correlationId")
            values (:id, :org, :project, :definitionId, :versionId, :conversationId, :trigger,
                    cast('RUNNING' as "WorkflowRunStatus"), :principal, cast(:scope as jsonb), 0, 5,
                    :now, :now, :now, 'test-ai-send-permission')
            """, new MapSqlParameterSource().addValue("id", runId)
                .addValue("org", WorkflowFixture.ORG_A).addValue("project", WorkflowFixture.PROJECT_A)
                .addValue("definitionId", definitionId).addValue("versionId", versionId)
                .addValue("conversationId", conversationId)
                .addValue("trigger", UUID.randomUUID().toString())
                .addValue("principal", WorkflowFixture.ADMIN_A)
                .addValue("scope", "[\"workflow:ai.send\"]").addValue("now", java.time.LocalDateTime.now(
                    java.time.ZoneOffset.UTC)));
        return runId;
    }

    private static String json(Map<String, Object> document) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the fixture document is not serialisable", e);
        }
    }

    private int aiMessages(UUID conversationId) {
        return fixture.count("""
            select count(*) from messages m
             where m."organizationId" = :org and m."conversationId" = :conversationId
               and m."authorType" = cast('AI' as "MessageAuthorType")
            """, Map.of("org", WorkflowFixture.ORG_A, "conversationId", conversationId));
    }

    private int pendingAiMessages(UUID conversationId) {
        return fixture.count("""
            select count(*) from messages m
             where m."organizationId" = :org and m."conversationId" = :conversationId
               and m."authorType" = cast('AI' as "MessageAuthorType")
               and m."deliveryStatus" = cast('PENDING' as "MessageDeliveryStatus")
            """, Map.of("org", WorkflowFixture.ORG_A, "conversationId", conversationId));
    }

    private String messageStatus(UUID messageId) {
        return jdbc.queryForObject(
            "select m.\"deliveryStatus\"::text from messages m where m.\"id\" = :id",
            new MapSqlParameterSource("id", messageId), String.class);
    }

    private int totalOutboundIntents() {
        return fixture.count("""
            select count(*) from outbox_events o
             where o."organizationId" = :org and o."eventType" = :eventType
            """, Map.of("org", WorkflowFixture.ORG_A,
            "eventType", com.firstelement.worker.queue.QueueName.OUTBOUND_SEND.wireValue()));
    }

    private int outboundIntentsFor(UUID messageId) {
        return fixture.count("""
            select count(*) from outbox_events o
             where o."organizationId" = :org
               and o."eventType" = :eventType
               and o."payload" ->> 'messageId' = :messageId
            """, Map.of("org", WorkflowFixture.ORG_A,
            "eventType", com.firstelement.worker.queue.QueueName.OUTBOUND_SEND.wireValue(),
            "messageId", messageId.toString()));
    }

    private int runActionCount(UUID runId) {
        return fixture.count(
            "select count(*) from workflow_run_actions where \"workflowRunId\" = :runId",
            Map.of("runId", runId));
    }

    private int deniedPermissionRows() {
        return fixture.count("""
            select count(*) from audit_logs a
             where a."organizationId" = :org
               and a."action" = cast(:action as text)
               and a."resourceType" = 'Permission'
            """, Map.of("org", WorkflowFixture.ORG_A,
            "action", com.firstelement.security.event.SecurityEventType.ACCESS_DENIED.auditAction()));
    }

    /**
     * The permissions named by every grant denial on the stream, de-duplicated.
     *
     * <p>Read out of {@code metadata->>'permission'}, which is where
     * {@code AuthorizationService#denyRole} puts it: {@code resourceId} stays null for a grant denial,
     * and the permission is the metadata. So the refusal names the missing permission on the audit
     * record, while the exception the caller sees is a bare {@code "Access denied"}.
     */
    private Set<Permission> deniedPermissionsInAudit() {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String value : jdbc.queryForList("""
            select a."metadata" ->> 'permission' as "permission" from audit_logs a
             where a."organizationId" = :org
               and a."action" = cast(:action as text)
               and a."resourceType" = 'Permission'
            """, new MapSqlParameterSource()
            .addValue("org", WorkflowFixture.ORG_A)
            .addValue("action", com.firstelement.security.event.SecurityEventType.ACCESS_DENIED
                .auditAction()),
            String.class)) {
            permissions.add(Permission.fromValue(value));
        }
        return permissions;
    }

    private List<String> denialActors() {
        return jdbc.queryForList("""
            select a."actorType"::text as "actorType" from audit_logs a
             where a."organizationId" = :org
               and a."action" = cast(:action as text)
               and a."resourceType" = 'Permission'
            """, new MapSqlParameterSource()
            .addValue("org", WorkflowFixture.ORG_A)
            .addValue("action",
                com.firstelement.security.event.SecurityEventType.ACCESS_DENIED.auditAction()),
            String.class);
    }

    private List<UUID> denialTenants() {
        return jdbc.queryForList("""
            select a."organizationId" as "organizationId" from audit_logs a
             where a."action" = cast(:action as text) and a."resourceType" = 'Permission'
            """, new MapSqlParameterSource()
            .addValue("action",
                com.firstelement.security.event.SecurityEventType.ACCESS_DENIED.auditAction()),
            UUID.class);
    }

    /** Every metadata document on the denial rows, for a no-customer-content assertion. */
    private List<String> denialMetadata() {
        return jdbc.queryForList("""
            select a."metadata"::text as "metadata" from audit_logs a
             where a."organizationId" = :org
               and a."action" = cast(:action as text)
               and a."resourceType" = 'Permission'
            """, new MapSqlParameterSource()
            .addValue("org", WorkflowFixture.ORG_A)
            .addValue("action",
                com.firstelement.security.event.SecurityEventType.ACCESS_DENIED.auditAction()),
            String.class);
    }
}