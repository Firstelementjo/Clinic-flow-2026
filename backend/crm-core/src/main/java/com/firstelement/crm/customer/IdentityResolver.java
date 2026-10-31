package com.firstelement.crm.customer;

import com.firstelement.crm.domain.CustomerLocale;
import com.firstelement.crm.persistence.CustomerIdentityRepository;
import com.firstelement.crm.persistence.CustomerRepository;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.timeline.CrmDomainEvent;
import com.firstelement.crm.timeline.ConversationTimelineStore;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an external channel handle into a customer. A port of
 * {@code packages/messaging/src/identity-resolver.ts}.
 *
 * <h2>Why it lives in {@code crm-core} and not in the messaging module</h2>
 *
 * <p>The legacy source says it outright: the logic lives in the shared package "because the
 * worker resolves identities on every inbound message without Nest in the picture. Two
 * implementations would mean two definitions of who a customer is." This is the single
 * definition; Phase 5's inbound pipeline and this phase's HTTP surface both call it.
 *
 * <h2>The rule that shapes the algorithm</h2>
 *
 * <blockquote>A new handle is not automatically a new person, and a similar name is not
 * automatically the same person.</blockquote>
 *
 * <p>Resolution therefore has exactly three outcomes:
 *
 * <ol>
 *   <li>The handle already exists → its customer, plus a {@code lastSeenAt} touch.</li>
 *   <li>A <em>verified</em> signal matches (phone or email) → attach the handle to that
 *       customer as verified. This is the only automatic link.</li>
 *   <li>Otherwise → create the customer, attach the handle, and report name-only candidates as
 *       {@code possibleMatches} for a human. They are never merged.</li>
 * </ol>
 *
 * <h2>Tenant scope</h2>
 *
 * <p>Every read and write goes through {@link CrmQueryScope}, which the caller can only obtain
 * from {@link TenantQueryGuard}. Resolution can therefore never attach a handle to, or create a
 * customer in, a project the caller cannot reach — which matters because Phase 5's worker will
 * call this with a connector's scope.
 */
@Service
public class IdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolver.class);

    /**
     * How many times a concurrent writer can win the
     * {@code customers_organizationId_cxId_key} /
     * {@code customer_identities_..._key} race before the caller is told to retry.
     *
     * <p><b>This is a contention bound and nothing else. Read it as "how many times may two
     * writers collide", not as "how many references exist".</b>
     *
     * <p>It used to be the latter, and that was defect CX-1 (ADR-0014). The candidate was a
     * project-scoped {@code count(customers)} while {@code cxId} was globally unique, so every
     * project searched from {@code #33000000}; five projects consumed
     * {@code #33000000}…{@code #33000004} and the sixth project's first customer threw. The
     * number 5 was not a retry count — it was the size of the deployment the product could
     * support. A retry loop over a shared namespace solves contention, not exhaustion.
     *
     * <p>The candidate now comes from {@code public.firstelement_next_customer_cx_id}, which holds
     * the tenant's high-water mark and increments it atomically, so two writers cannot be handed
     * the same reference and the loop below should essentially never run. Exhaustion is impossible:
     * the namespace is 1000000 references per organization and no reference is ever reused. If
     * this loop does exhaust, the cause is contention or a broken allocator — never scarcity.
     */
    private static final int MAX_IDENTITY_ATTEMPTS = 5;

    private final CustomerRepository customers;
    private final CustomerIdentityRepository identities;
    private final ConversationTimelineStore timeline;
    private final TenantQueryGuard guard;
    private final JsonCodec json;

    public IdentityResolver(CustomerRepository customers,
                            CustomerIdentityRepository identities,
                            ConversationTimelineStore timeline,
                            TenantQueryGuard guard,
                            JsonCodec json) {
        this.customers = customers;
        this.identities = identities;
        this.timeline = timeline;
        this.guard = guard;
        this.json = json;
    }

    /**
     * Resolves {@code identity} to a customer.
     *
     * <p>Requires a bound {@link TenantContext}: the resolver is reachable from a request and
     * from a worker job, and both must carry a scope. The context's organization must match the
     * identity's claimed organization, so a job payload cannot switch tenants.
     */
    @Transactional
    public ResolvedCustomer resolve(TenantContext context, InboundIdentity identity) {
        CrmQueryScope scope = resolveScope(context, identity);
        LocalDateTime now = LocalDateTime.now();

        Optional<CustomerIdentityRepository.IdentityRow> existing =
            identities.findByHandle(scope, identity.platform(), identity.externalId());
        if (existing.isPresent()) {
            CustomerIdentityRepository.IdentityRow row = existing.get();
            customers.touchLastSeen(scope, row.customerId(), now);
            return new ResolvedCustomer(row.customerId(), row.id(), false, List.of());
        }

        Optional<UUID> verifiedMatch = findVerifiedMatch(scope, identity);
        if (verifiedMatch.isPresent()) {
            UUID customerId = verifiedMatch.get();
            CustomerIdentityRepository.IdentityRow attached =
                attachIdentity(scope, identity, customerId, true, now);
            return new ResolvedCustomer(customerId, attached.id(), false, List.of());
        }

        List<ResolvedCustomer.PossibleMatch> weakMatches = findWeakMatches(scope, identity);

        for (int attempt = 0; attempt < MAX_IDENTITY_ATTEMPTS; attempt++) {
            // The reference comes from the database allocator, which holds this tenant's
            // high-water mark and increments it atomically. It is already formatted
            // (`#33` + six digits) — do NOT pass it through TicketNumber.generateCxId again.
            String cxId = customers.nextCxId(scope);
            UUID customerId = UUID.randomUUID();
            try {
                customers.insert(scope, customerId, cxId, resolveDisplayName(identity),
                    identity.email(), identity.phone(), resolveLocale(identity),
                    externalIdsJson(identity), now);
            } catch (DuplicateKeyException e) {
                // GENUINE WRITE CONTENTENTION, and the only thing this loop is for. Two
                // deliveries for the same tenant each asked the allocator, so the constraint
                // should never fire -- but if it does, another writer won this exact value
                // between the allocation and the insert, and asking again returns a fresh one.
                //
                // This is deliberately NOT a capacity mechanism (ADR-0014). It used to be one,
                // and that is the bug: with a count()-derived candidate and a globally unique
                // key, `attempts` was really "the number of references the whole deployment
                // could ever hold" -- five. Exhaustion is now impossible; the namespace is
                // 1000000 references per organization, and a reference is never reused.
                // If this loop ever fails, the cause is contention or a broken allocator --
                // never scarcity. Do not widen this constant to paper over either.
                log.debug("cxId {} was taken concurrently; asking the allocator again", cxId);
                continue;
            }

            CustomerIdentityRepository.IdentityRow attached;
            try {
                attached = attachIdentity(scope, identity, customerId,
                    identity.phone() != null || identity.email() != null, now);
            } catch (CustomerIdentityRepository.DuplicateIdentityException e) {
                // A concurrent delivery attached the same handle between the lookup and here.
                // That delivery's customer is authoritative; return it rather than creating an
                // orphan customer.
                CustomerIdentityRepository.IdentityRow winner =
                    identities.findByHandle(scope, identity.platform(), identity.externalId())
                        .orElseThrow(() -> e);
                return new ResolvedCustomer(winner.customerId(), winner.id(), false, List.of());
            }

            publishCustomerCreated(identity, customerId, now);
            return new ResolvedCustomer(customerId, attached.id(), true, weakMatches);
        }

        throw new IllegalStateException(
            "Could not allocate a customer reference after " + MAX_IDENTITY_ATTEMPTS
                + " attempts. This is write contention or a broken allocator, NOT an exhausted "
                + "reference namespace: the allocator holds a high-water mark, so scarcity cannot "
                + "be the cause. Do not widen MAX_IDENTITY_ATTEMPTS to make this disappear -- "
                + "ADR-0014 records what that looked like last time.");
    }

    // ------------------------------------------------------------------

    /**
     * The scope for a resolution.
     *
     * <p>The organization comes from the authenticated principal; the identity's claimed
     * organization is a request, never authorization, and a mismatch is a denial.
     */
    private CrmQueryScope resolveScope(TenantContext context, InboundIdentity identity) {
        if (context == null) {
            throw new IllegalStateException(
                "Identity resolution requires a bound TenantContext; refusing to resolve unscoped.");
        }
        guard.claimedTenantScope(context, identity.organizationId());
        return CrmQueryScope.forProject(guard, context, identity.projectId());
    }

    /** Phone and email are the only signals strong enough to link without asking. */
    private Optional<UUID> findVerifiedMatch(CrmQueryScope scope, InboundIdentity identity) {
        if (identity.phone() == null && identity.email() == null) {
            return Optional.empty();
        }
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (identity.phone() != null) {
            conditions.add("c.\"primaryPhone\" = :phone");
            parameters.put("phone", identity.phone());
        }
        if (identity.email() != null) {
            conditions.add("c.\"primaryEmail\" = :email");
            parameters.put("email", identity.email());
        }
        CrmQueryScope.Statement statement = scope.select(
            "select c.\"id\" from customers c", "c",
            "(" + String.join(" or ", conditions) + ")", parameters)
            .orderedBy("c.\"lastSeenAt\" desc", 1);
        return customers.listIds(statement).stream().findFirst();
    }

    /**
     * Name-only overlap. Reported, never acted on.
     *
     * <p>The legacy threshold of three characters is kept: a one or two character name matches
     * almost everything and would make the suggestion list noise.
     */
    private List<ResolvedCustomer.PossibleMatch> findWeakMatches(CrmQueryScope scope,
                                                                 InboundIdentity identity) {
        String name = identity.displayName() == null ? null : identity.displayName().trim();
        if (name == null || name.length() < 3) {
            return List.of();
        }
        return customers.findByDisplayName(scope, name, 5).stream()
            .map(customer -> new ResolvedCustomer.PossibleMatch(customer.id(), customer.displayName(),
                ResolvedCustomer.PossibleMatch.REASON_NAME))
            .toList();
    }

    private CustomerIdentityRepository.IdentityRow attachIdentity(CrmQueryScope scope,
                                                                  InboundIdentity identity,
                                                                  UUID customerId,
                                                                  boolean isVerified,
                                                                  LocalDateTime now) {
        return identities.insert(scope, UUID.randomUUID(), customerId, identity.platform(),
            identity.externalId(), identity.displayName(), isVerified,
            json.write(identity.rawProfile()), now);
    }

    /**
     * The name shown in the inbox.
     *
     * <p>Ported from the legacy {@code fallbackName}: show the handle rather than "Unknown",
     * because an operator can act on a phone number and cannot act on a placeholder.
     */
    public static String resolveDisplayName(InboundIdentity identity) {
        if (identity.displayName() != null && !identity.displayName().trim().isEmpty()) {
            return identity.displayName().trim();
        }
        if (identity.phone() != null && !identity.phone().isBlank()) {
            return identity.phone();
        }
        String externalId = identity.externalId();
        String tail = externalId.length() <= 6 ? externalId
            : externalId.substring(externalId.length() - 6);
        return identity.platform().name() + " " + tail;
    }

    private static String resolveLocale(InboundIdentity identity) {
        if (identity.locale() == null) {
            return CustomerLocale.AR.value();
        }
        try {
            return CustomerLocale.fromValue(identity.locale()).value();
        } catch (IllegalArgumentException e) {
            return CustomerLocale.AR.value();
        }
    }

    /** {@code customers."externalIds"} — the convenience read-model, keyed by channel. */
    static String externalIdsJson(InboundIdentity identity) {
        return "{\"" + identity.platform().name() + "\":\""
            + com.firstelement.crm.persistence.MessageRepository.jsonEscape(identity.externalId())
            + "\"}";
    }

    private void publishCustomerCreated(InboundIdentity identity, UUID customerId, LocalDateTime now) {
        // CUSTOMER_CREATED has no conversation to hang from, so the timeline recorder drops it —
        // exactly as the legacy mapper did. It is still published so a Phase 5 subscriber can
        // react; the recorder simply writes no row.
        CrmDomainEvent event = CrmDomainEvent.customerCreated(identity.organizationId(),
            identity.projectId(), customerId, identity.platform().name(), identity.externalId(), now);
        timeline.append(event);
    }
}