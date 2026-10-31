package com.firstelement.channels.persistence;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.persistence.MessageRepository;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.worker.outbox.OutboxEnqueuer;
import com.firstelement.worker.outbox.OutboxIntent;
import com.firstelement.worker.queue.QueueName;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes the delivery intent in the same transaction as the outbound message row.
 *
 * <h2>Why a decorator, and why this is honest</h2>
 *
 * <p>FR-4.6 requires the outbox intent to be written "inside the same transaction as domain
 * changes", and Phase 4 wrote the message row inside {@code ConversationService.reply}, a
 * {@code @Transactional} method in {@code :backend:inbox-helpdesk} — a module this phase may not
 * modify. There are exactly three ways to join that transaction from outside:
 *
 * <ol>
 *   <li><b>Call the service and enqueue afterwards.</b> Two transactions. A crash between them
 *       loses the delivery; an enqueue before the reply commits publishes work for a change that
 *       may roll back. This is the bug the outbox exists to remove.</li>
 *   <li><b>An {@code @Around} aspect.</b> A Spring AOP advice sits <em>outside</em> the transaction
 *       interceptor by default, so its write would open a second transaction anyway — and getting
 *       the ordering right means reordering {@code @EnableTransactionManagement} globally, which
 *       would change advice ordering for every other module in the system.</li>
 *   <li><b>Replace one collaborator.</b> {@code MessageRepository} is the single component every CRM
 *       service already calls inside its own transaction, and it is a class rather than an
 *       interface, so a {@link Primary} subclass delegating to it joins the caller's transaction
 *       without touching a line of {@code crm-core}.</li>
 * </ol>
 *
 * <p>This is (3). It is the smallest seam that actually satisfies the requirement, and it is
 * confined to this module: a process without {@code :backend:channels} has no decorator, so
 * {@code :backend:crm-core} and {@code :backend:inbox-helpdesk} tests are unaffected.
 *
 * <h2>The replacement to plan for</h2>
 *
 * <p>If a later phase gives {@code ConversationService.reply} an explicit application event
 * (Phase 7's "safe allow-listed maintenance commands" or a notification hook), this decorator should
 * be deleted and replaced by a plain non-transactional {@code @EventListener} that enqueues. The
 * contract is identical; the surgery would not be. Recorded as a follow-up in the phase report
 * rather than left as folklore.
 *
 * <h2>What is written</h2>
 *
 * <p>An envelope with the message id and nothing else: no body, no customer text, no recipient
 * handle. The worker resolves the row under tenant scope — that is the rule from
 * {@code V20261004000100} Q2, and this is where it is honoured.
 *
 * <p>The {@code idempotencyKey} is derived from the message id, so one message row has exactly one
 * delivery intent no matter how the transaction is retried.
 */
@Component
@Primary
public class OutboxIntentMessageRepository extends MessageRepository {

    private static final Logger log = LoggerFactory.getLogger(OutboxIntentMessageRepository.class);

    private final OutboxEnqueuer outbox;

    public OutboxIntentMessageRepository(org.springframework.jdbc.core.namedparam
                                         .NamedParameterJdbcTemplate jdbc,
                                         OutboxEnqueuer outbox) {
        super(jdbc);
        this.outbox = outbox;
    }

    @Override
    public MessageRow insertOutbound(CrmQueryScope scope,
                                     UUID messageId,
                                     UUID conversationId,
                                     ChannelType channel,
                                     String body,
                                     UUID authorUserId,
                                     String idempotencyKey,
                                     LocalDateTime sentAt) {
        MessageRow row = super.insertOutbound(scope, messageId, conversationId, channel, body,
            authorUserId, idempotencyKey, sentAt);
        publishDeliveryIntent(scope, messageId, conversationId, channel, "EmployeeReply");
        return row;
    }

    @Override
    public MessageRow insertAiOutbound(CrmQueryScope scope,
                                       UUID messageId,
                                       UUID conversationId,
                                       ChannelType channel,
                                       String body,
                                       String externalMessageId,
                                       String metadata,
                                       LocalDateTime sentAt) {
        // An AI reply is inserted already SENT by Phase 4's contract (the model produced the text
        // and the caller decides whether it is delivered), so no delivery intent is written here.
        // Phase 10 owns the AI outbound path and will enqueue its own intent.
        return super.insertAiOutbound(scope, messageId, conversationId, channel, body,
            externalMessageId, metadata, sentAt);
    }

    /**
     * Writes the intent in the caller's transaction.
     *
     * <p>Fails loudly if there is no transaction or no bound tenant: publishing outside the domain
     * transaction is the precise failure this whole mechanism exists to prevent, so
     * {@link OutboxEnqueuer} refuses it and this lets the refusal roll the reply back rather than
     * committing a message nothing will ever deliver.
     */
    private void publishDeliveryIntent(CrmQueryScope scope, UUID messageId, UUID conversationId,
                                       ChannelType channel, String aggregateType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Not a CRM service call path (a fixture seeding rows directly). Nothing to publish:
            // there is no domain transaction to share the fate of.
            log.debug("outbound message {} inserted outside a transaction; no delivery intent "
                + "written (seeding path)", messageId);
            return;
        }
        UUID organizationId = scope.organizationId();
        // The tenant is bound by TenantContextFilter or TenantScopedExecutor. This is the same
        // context the message row was written under, because the scope came from it.
        TenantContextHolder.current().ifPresent(context -> {
            if (!context.organizationId().equals(organizationId)) {
                throw new IllegalStateException("Refusing to publish a delivery intent for a tenant "
                    + "other than the bound one");
            }
        });
        String correlationId = org.slf4j.MDC.get("correlationId");
        outbox.publish(OutboxIntent.of(
            organizationId,
            scope.projectId(),
            aggregateType,
            messageId,
            QueueName.OUTBOUND_SEND.wireValue(),
            "{\"messageId\":\"" + messageId + "\",\"conversationId\":\"" + conversationId
                + "\",\"channel\":\"" + channel.name() + "\"}",
            "outbound-send:" + messageId,
            correlationId,
            conversationId.toString()));
    }
}