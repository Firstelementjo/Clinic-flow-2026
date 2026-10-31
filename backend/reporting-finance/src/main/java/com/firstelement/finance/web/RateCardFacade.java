package com.firstelement.finance.web;

import com.firstelement.finance.rates.RateCardRepository;
import com.firstelement.finance.rates.RateCardService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The finance controller's single dependency for rate cards.
 *
 * <h2>Why a facade rather than the repository</h2>
 *
 * <p>Because the controller must not reach past the service layer's permission check. If it injected
 * {@link RateCardRepository} directly, {@code @RequirePermission} on the route would be the <em>only</em>
 * enforcement — and plan.md §0.4 requires the domain service to enforce it as well, because a read model
 * is reachable from more than HTTP.
 *
 * <h2>Why rate-card authoring is not on the route set</h2>
 *
 * <p>Because ADR 0007 puts rate cards under the administrator capability set, and authoring involves
 * choices — a markup, a currency, an effective date — that a plain HTTP write would make too easy to get
 * wrong. {@link RateCardService} enforces the {@code ADMIN} role, requires a human actor (every author
 * column is a composite tenant foreign key to {@code users}), requires a change note, and refuses an
 * overlapping effective range. Exposing those as routes is a deliberate later decision with its own
 * approval workflow, not an omission — recorded as follow-up debt.
 *
 * <p>Reads are routed, because a reviewer has to see the card and its history to review anything.
 */
@Component
public class RateCardFacade {

    private final RateCardService service;

    public RateCardFacade(RateCardService service) {
        this.service = service;
    }

    /**
     * The tenant's rate cards, whatever their status.
     *
     * <p>Declared even though the delegate already enforces it: a facade is a Spring bean, so it is a
     * reachable entry point, and {@code FinanceRoutePermissionTest} requires every public method of a
     * finance service that takes a caller to say so. Defence in depth is cheap on a two-line delegate
     * and expensive to retrofit.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<RateCardRepository.RateCardRow> cards(TenantContext caller, UUID projectId) {
        return service.cards(caller, projectId);
    }

    /**
     * One card's full version history, newest first, including retired versions.
     *
     * <p>Retired versions are included on purpose: a reviewer asking "what price applied in March" needs
     * the version that was retired in April, and a history that hides it answers a different question
     * than the one asked.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    public List<java.util.Map<String, Object>> history(TenantContext caller, UUID rateCardId) {
        return service.history(caller, rateCardId);
    }
}
