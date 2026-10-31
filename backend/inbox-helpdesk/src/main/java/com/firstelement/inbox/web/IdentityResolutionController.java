package com.firstelement.inbox.web;

import com.firstelement.crm.customer.IdentityResolver;
import com.firstelement.crm.customer.InboundIdentity;
import com.firstelement.crm.customer.ResolvedCustomer;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.inbox.api.ResolveIdentityRequest;
import com.firstelement.inbox.api.ResolvedIdentityResponse;
import com.firstelement.security.TenantContextHolder;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/customers/resolve-identity} — the operator-facing identity resolution
 * surface.
 *
 * <h2>Why this is exposed</h2>
 *
 * <p>The resolver is the single definition of "who is this person". In the legacy runtime it was
 * only reachable from the worker and from one integration test, so an operator who saw the same
 * phone number on two customers had no way to ask the system. It is exposed here so the system
 * can answer.
 *
 * <h2>What it will not do</h2>
 *
 * <ul>
 *   <li>It does not merge anything. A name-only candidate comes back in {@code possibleMatches}
 *       with {@code reason: "NAME"} and stays a suggestion — the legacy behaviour, and the reason
 *       two people's transcripts are never silently merged.</li>
 *   <li>It does not accept a tenant id. The organization comes from the authenticated principal;
 *       the project is validated against the caller's server-resolved scope by
 *       {@code TenantQueryGuard}.</li>
 * </ul>
 *
 * <p>Declared after {@link CustomersController} on the same path prefix, and the literal
 * {@code /resolve-identity} pattern wins over {@code /{customerId}}, so this never collides with
 * the UUID routes.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class IdentityResolutionController {

    private final IdentityResolver identities;

    public IdentityResolutionController(IdentityResolver identities) {
        this.identities = identities;
    }

    @PostMapping("/resolve-identity")
    public ResolvedIdentityResponse resolve(@RequestBody ResolveIdentityRequest body) {
        var context = TenantContextHolder.get();
        if (body == null) {
            throw new ValidationFailedException("A body is required");
        }
        UUID projectId = body.projectId();
        ChannelType channel = body.platform() == null ? null : ChannelType.fromValue(body.platform());

        ResolvedCustomer resolved = identities.resolve(context, new InboundIdentity(
            context.organizationId(), projectId, channel, body.externalId(), body.displayName(),
            body.phone(), body.email(), body.locale(), body.rawProfile()));

        return new ResolvedIdentityResponse(
            resolved.customerId(),
            resolved.identityId(),
            resolved.created(),
            resolved.possibleMatches().stream()
                .map(match -> new ResolvedIdentityResponse.PossibleMatch(
                    match.customerId(), match.displayName(), match.reason()))
                .toList());
    }
}