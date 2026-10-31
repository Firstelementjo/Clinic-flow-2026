package com.firstelement.inbox.service;

import com.firstelement.crm.customer.IdentityResolver;
import com.firstelement.crm.customer.InboundIdentity;
import com.firstelement.crm.customer.ResolvedCustomer;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.ConversationMode;
import com.firstelement.crm.domain.ConversationStatus;
import com.firstelement.crm.domain.TicketNumber;
import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.persistence.ConversationRow;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.persistence.MessageRepository;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.timeline.ConversationTimelineStore;
import com.firstelement.crm.timeline.CrmDomainEvent;
import com.firstelement.inbox.api.InboundResult;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbound half of the conversation lifecycle: webhook → customer → conversation → message.
 * A port of {@code packages/messaging/src/inbound-pipeline.ts}.
 *
 * <h2>One open conversation per customer per channel</h2>
 *
 * <p>A closed thread plus a new message starts a new conversation, and a resolved thread is
 * reopened, exactly as the legacy pipeline did. That keeps resolution times and AI runs
 * attributable to the exchange they belong to — splitting a customer's history across two
 * threads would make every metric a lie.
 *
 * <h2>Idempotency is decided by the database, not by a prior SELECT</h2>
 *
 * <p>The legacy comment states the reason: "a prior SELECT would race under concurrent
 * deliveries and double-post to the customer". So this method does a best-effort
 * find-or-create and lets
 * {@code messages_projectId_channel_externalMessageId_key} be the arbiter; a violation becomes
 * {@link InboundResult.Status#DUPLICATE} rather than an error.
 *
 * <h2>AI mode on creation</h2>
 *
 * <p>A project with a live agent answers with AI; one without waits for a person. Defaulting to
 * HUMAN is the safe direction. The agent lookup is Phase 10's ({@code ai_agents} + its active
 * version) and is behind {@link #hasActiveAgent} so this class compiles and runs before it; the
 * default is therefore {@code HUMAN}, which is the safe side of the choice.
 *
 * <h2>No external call</h2>
 *
 * <p>Everything here is database work. Nothing in this path calls an AI provider or a channel
 * API, which is what keeps it usable when either is down.
 */
@Service
public class InboundMessageService {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageService.class);

    /**
     * How far the ticket sequence is walked when two threads are created in the same instant.
     *
     * <p>{@code conversations_ticketNumber_key} is globally unique and the sequence is
     * tenant-scoped, so two tenants can compute the same candidate. Walking forward finds a free
     * number instead of failing the customer's message.
     */
    private static final int MAX_TICKET_ATTEMPTS = 25;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final IdentityResolver identities;
    private final ConversationTimelineStore timeline;
    private final JsonCodec json;
    private final TenantQueryGuard guard;

    public InboundMessageService(ConversationRepository conversations,
                                 MessageRepository messages,
                                 IdentityResolver identities,
                                 ConversationTimelineStore timeline,
                                 JsonCodec json,
                                 TenantQueryGuard guard) {
        this.conversations = conversations;
        this.messages = messages;
        this.identities = identities;
        this.timeline = timeline;
        this.json = json;
        this.guard = guard;
    }

    /**
     * Processes one inbound customer message.
     *
     * <p>Requires {@code CONVERSATION_REPLY}: the caller is either a verified connector principal
     * or, for the development simulator, an authenticated employee with access to the target
     * project. A job payload's {@code projectId} is a request; the scope below is the
     * authorization.
     */
    @RequirePermission(Permission.CONVERSATION_REPLY)
    @Transactional
    public InboundResult receive(TenantContext context,
                                 UUID projectId,
                                 UUID connectionId,
                                 IncomingMessage message) {
        if (message == null || message.channel() == null) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                "A channel and a message body are required");
        }
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);

        ResolvedCustomer customer = identities.resolve(context, new InboundIdentity(
            context.organizationId(), projectId, message.channel(), message.externalUserId(),
            message.displayName(), message.phone(), message.email(), null, message.metadata()));

        Thread thread = findOrCreateThread(scope, context, customer.customerId(),
            message.channel(), message.sentAt());

        UUID messageId = UUID.randomUUID();
        try {
            messages.insertInbound(scope, messageId, thread.conversationId(), message.channel(),
                message.text() == null ? "" : message.text(), message.externalMessageId(),
                json.write(message.attachments()),
                json.write(java.util.Map.of("connectionId",
                    connectionId == null ? "" : connectionId.toString())),
                message.sentAt());
        } catch (MessageRepository.DuplicateInboundMessageException e) {
            log.info("inbound message {} was already stored; not writing a second copy",
                message.externalMessageId());
            return InboundResult.duplicate(thread.conversationId(), message.externalMessageId());
        }

        conversations.applyInboundCounters(scope, thread.conversationId(), message.sentAt(),
            LocalDateTime.now());

        timeline.append(CrmDomainEvent.messageCreated(context.organizationId(), projectId,
            thread.conversationId(), customer.customerId(), messageId, message.channel().name(),
            com.firstelement.crm.domain.MessageDirection.INBOUND.name(),
            com.firstelement.crm.domain.MessageAuthorType.CUSTOMER.name(),
            message.text() == null ? 0 : message.text().length(), message.sentAt(), null));

        return InboundResult.processed(thread.conversationId(), customer.customerId(), messageId,
            thread.created(), message.externalMessageId());
    }

    // ------------------------------------------------------------------

    /** The conversation the message belongs to, created if the customer has no live thread. */
    private Thread findOrCreateThread(CrmQueryScope scope,
                                       TenantContext context,
                                       UUID customerId,
                                       ChannelType channel,
                                       LocalDateTime sentAt) {
        java.util.Optional<ConversationRow> latest =
            conversations.findLatestThreadForCustomer(scope, customerId, channel);

        if (latest.isPresent()) {
            ConversationRow row = latest.get();
            if (ConversationStatus.RESOLVED.name().equals(row.status())) {
                conversations.reopenResolved(scope, row.id(), LocalDateTime.now());
            }
            return new Thread(row.id(), false);
        }

        // A project with a live agent answers with AI; one without waits for a person.
        ConversationMode mode = hasActiveAgent(context, scope.projectId())
            ? ConversationMode.AI
            : ConversationMode.HUMAN;

        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 0; attempt < MAX_TICKET_ATTEMPTS; attempt++) {
            String ticketNumber = TicketNumber.generateTicketNumber(
                sentAt, conversations.nextTicketSequence(scope, sentAt) + attempt);
            if (conversations.ticketNumberExists(ticketNumber)) {
                continue;
            }
            UUID conversationId = UUID.randomUUID();
            try {
                conversations.insert(scope, conversationId, customerId, channel,
                    ConversationStatus.OPEN, mode, ticketNumber, sentAt, now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.debug("ticket {} was taken concurrently; walking the sequence forward",
                    ticketNumber);
                continue;
            }
            timeline.append(CrmDomainEvent.conversationCreated(context.organizationId(),
                scope.projectId(), conversationId, customerId, channel.name(), mode.name(),
                sentAt));
            return new Thread(conversationId, true);
        }

        throw new IllegalStateException(
            "Could not allocate a ticket number after " + MAX_TICKET_ATTEMPTS + " attempts");
    }

    /**
     * Whether the project has an enabled AI agent.
     *
     * <p>Phase 4 has no AI module, so this is always {@code false} and new threads start in
     * HUMAN mode. That is the safe direction: "Defaulting to HUMAN is the safe direction
     * (plan §20.1)". Phase 10 replaces the body with the {@code ai_agents} lookup; the call site
     * does not change, and the escalation/takeover journey continues to work because a takeover
     * from HUMAN is a legal transition.
     */
    protected boolean hasActiveAgent(TenantContext context, UUID projectId) {
        return false;
    }

    private record Thread(UUID conversationId, boolean created) {
    }
}