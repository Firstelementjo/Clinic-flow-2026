package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.channels.persistence.OutboxIntentMessageRepository;
import com.firstelement.inbox.api.SendMessageRequest;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.worker.queue.QueueName;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-4.6: the outbox intent is written in the SAME transaction as the domain change.
 *
 * <h2>Why this suite exists at all</h2>
 *
 * <p>"Persist before external send" is a property of <em>transactions</em>, not of code review. The
 * legacy documentation states the failure it prevents: "a send that succeeded while the write fails
 * leaves a customer holding a reply the CRM has no record of, and the agent repeats themselves."
 * So this suite drives the real {@code ConversationService.reply} — the Phase-4 method this phase
 * may not modify — and asserts three things:
 *
 * <ol>
 *   <li>a committed reply leaves exactly one delivery intent;</li>
 *   <li>a rolled-back reply leaves none;</li>
 *   <li>the intent's payload carries the message id and no customer text.</li>
 * </ol>
 *
 * <p>The seam is {@link OutboxIntentMessageRepository}, a {@code @Primary} decorator over
 * {@code MessageRepository}. Its own documentation explains why that is the only way to join the
 * transaction from outside {@code :backend:inbox-helpdesk}, and what to replace it with.
 */
@SpringBootTest(classes = ChannelsTestApplication.class)
class OutboxSameTransactionIntegrationTest extends ChannelsIntegrationTestBase {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private OutboxIntentMessageRepository messageRepository;
    @Autowired
    private ChannelsFixture fixture;
    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("the decorator is the primary MessageRepository, so every CRM service joins the outbox")
    void theDecoratorIsWired() {
        // The injection point in ConversationService is the plain MessageRepository; @Primary is what
        // makes it receive the decorator instead. The target class is compared rather than the
        // runtime class because a transactional proxy may wrap it.
        assertThat(messageRepository).isInstanceOf(OutboxIntentMessageRepository.class);
        assertThat(org.springframework.aop.support.AopUtils.getTargetClass(messageRepository))
            .isEqualTo(OutboxIntentMessageRepository.class);
    }

    @Test
    @DisplayName("a committed reply writes the message row and exactly one delivery intent")
    void committedReplyPublishesExactlyOneIntent() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        var result = as(staffA(), () -> conversations.reply(staffA(), conversationId,
            new SendMessageRequest("مرحبا، كيف حالك؟"), "reply-key-1", null, "127.0.0.1"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.messageId()).isNotNull();

        assertThat(fixture.count("select count(*) from messages where \"id\" = '"
            + result.messageId() + "'")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);

        String payload = fixture.jdbc().queryForObject(
            "select \"payload\"::text from outbox_events",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), String.class);

        // The envelope is ids only. The worker reads the row; customer text never enters the queue.
        assertThat(payload).contains(result.messageId().toString())
            .contains(conversationId.toString())
            .contains("MOCK");
        assertThat(payload).doesNotContain("مرحبا");
        assertThat(fixture.count("select count(*) from outbox_events where \"eventType\" = '"
            + QueueName.OUTBOUND_SEND.wireValue() + "'")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events where \"aggregateId\" = '"
            + result.messageId() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the intent's idempotency key is derived from the message, so one row has one intent")
    void idempotencyKeyIsDerivedFromTheMessage() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        var result = as(staffA(), () -> conversations.reply(staffA(), conversationId,
            new SendMessageRequest("hello"), "reply-key-2", null, null));

        assertThat(fixture.count("select count(*) from outbox_events where \"idempotencyKey\" = "
            + "'outbound-send:" + result.messageId() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an idempotent reply replay writes nothing: no second message, no second intent")
    void replayedReplyWritesNothing() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        var first = as(staffA(), () -> conversations.reply(staffA(), conversationId,
            new SendMessageRequest("hello"), "reply-key-3", null, null));
        var second = as(staffA(), () -> conversations.reply(staffA(), conversationId,
            new SendMessageRequest("hello"), "reply-key-3", null, null));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.messageId()).isEqualTo(first.messageId());
        assertThat(fixture.count("select count(*) from messages")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back reply publishes nothing: the intent shares the transaction's fate")
    void rolledBackReplyPublishesNothing() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        // The message row and the intent are written, then the transaction is rolled back. Both must
        // disappear together — the assertion that separates an outbox from a queue written
        // "afterwards", and the reason the enqueue lives inside the domain transaction at all.
        as(staffA(), () -> transactions.execute(status -> {
            conversations.reply(staffA(), conversationId, new SendMessageRequest("hello"),
                "reply-key-4", null, null);
            status.setRollbackOnly();
            return null;
        }));

        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
        assertThat(fixture.count("select count(*) from messages")).isZero();
    }

    @Test
    @DisplayName("a committed reply inside an outer transaction is durable")
    void committedReplyIsDurable() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        as(staffA(), () -> transactions.execute(status ->
            conversations.reply(staffA(), conversationId, new SendMessageRequest("hello"),
                "reply-key-4b", null, null)));

        assertThat(fixture.count("select count(*) from messages")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("an invalid body is refused before any write at all")
    void invalidBodyWritesNothing() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        assertThatThrownBy(() -> as(staffA(), () -> conversations.reply(staffA(), conversationId,
            new SendMessageRequest("   "), "reply-key-5", null, null)))
            .isInstanceOf(com.firstelement.crm.error.ValidationFailedException.class);

        assertThat(fixture.count("select count(*) from messages")).isZero();
        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
    }

    @Test
    @DisplayName("another tenant's conversation cannot be replied to, so it cannot publish an intent")
    void crossTenantReplyPublishesNothing() {
        UUID alphaConversation = fixture.conversation(ChannelsFixture.ORG_A,
            ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        // Beta's admin names Alpha's conversation. The guard refuses before the write, so there is
        // no message and — critically — no outbox row either.
        assertThatThrownBy(() -> as(adminB(), () -> conversations.reply(adminB(), alphaConversation,
            new SendMessageRequest("hello"), "reply-key-6", null, null)))
            .isInstanceOf(RuntimeException.class);

        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
        assertThat(fixture.count("select count(*) from messages")).isZero();
    }

    @Test
    @DisplayName("an employee with no membership of the project publishes nothing")
    void outsiderPublishesNothing() {
        UUID alphaConversation = fixture.conversation(ChannelsFixture.ORG_A,
            ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        TenantContext outsider = TenantContext.forUser(UUID.randomUUID(), ChannelsFixture.USER_A,
            ChannelsFixture.ORG_A, UserRole.EMPLOYEE, Set.of());

        assertThatThrownBy(() -> as(outsider, () -> conversations.reply(outsider, alphaConversation,
            new SendMessageRequest("hello"), "reply-key-7", null, null)))
            .isInstanceOf(RuntimeException.class);

        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
    }

    @Test
    @DisplayName("seeding rows outside a transaction writes no intent, because there is no domain change")
    void seedingOutsideATransactionPublishesNothing() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        // The repository is called directly with no transaction: the decorator must not invent one,
        // and must not publish work for a fixture row that no domain change produced.
        as(staffA(), () -> messageRepository.insertOutbound(
            com.firstelement.crm.query.CrmQueryScope.forProject(
                new com.firstelement.security.query.TenantQueryGuard(event -> { }), staffA(),
                ChannelsFixture.PROJECT_A),
            UUID.randomUUID(), conversationId,
            com.firstelement.crm.domain.ChannelType.MOCK, "seeded", staffA().userId(), null,
            com.firstelement.worker.config.UtcTime.now()));

        assertThat(fixture.count("select count(*) from outbox_events")).isZero();
        assertThat(fixture.count("select count(*) from messages")).isEqualTo(1);
    }

    @Test
    @DisplayName("every intent this phase writes is one of the declared queues")
    void everyIntentNamesADeclaredQueue() {
        UUID conversationId = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        as(staffA(), () -> conversations.reply(staffA(), conversationId,
            new SendMessageRequest("hello"), "reply-key-8", null, null));

        List<String> eventTypes = fixture.jdbc().queryForList("select \"eventType\" from outbox_events",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), String.class);
        assertThat(eventTypes).isNotEmpty();
        eventTypes.forEach(eventType ->
            assertThat(QueueName.fromWireValue(eventType)).as(eventType).isNotNull());
    }

    // ------------------------------------------------------------------

    private static TenantContext staffA() {
        return TenantContext.forUser(UUID.randomUUID(), ChannelsFixture.USER_A, ChannelsFixture.ORG_A,
            UserRole.EMPLOYEE, Set.of(ChannelsFixture.PROJECT_A));
    }

    private static TenantContext adminB() {
        return TenantContext.forUser(UUID.randomUUID(), ChannelsFixture.USER_B, ChannelsFixture.ORG_B,
            UserRole.ADMIN, Set.of(ChannelsFixture.PROJECT_B));
    }

    private static <T> T as(TenantContext context, java.util.function.Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(authentication(context));
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}
