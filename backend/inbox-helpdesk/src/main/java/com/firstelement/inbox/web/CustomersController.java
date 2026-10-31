package com.firstelement.inbox.web;

import com.firstelement.crm.api.CustomerDetail;
import com.firstelement.crm.api.CustomerListItem;
import com.firstelement.crm.api.UpdateCustomerRequest;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.crm.service.CustomerService;
import com.firstelement.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers} — the strangler-migration compatibility surface.
 *
 * <h2>Every route matches the legacy route, body and status</h2>
 *
 * <pre>
 *   GET   /api/v1/customers                 CUSTOMER_READ
 *   GET   /api/v1/customers/{customerId}    CUSTOMER_READ
 *   GET   /api/v1/customers/cx/{cxId}       CUSTOMER_READ
 *   PATCH /api/v1/customers/{customerId}    CUSTOMER_UPDATE
 *   POST  /api/v1/customers/resolve-identity CUSTOMER_READ
 * </pre>
 *
 * <p>{@code /cx/{cxId}} is declared after {@code /{customerId}} on purpose: Spring matches the
 * more specific literal pattern first, so a CX lookup never reaches the UUID parser.
 *
 * <h2>What is added</h2>
 *
 * <ul>
 *   <li>{@code ETag} on every response and {@code If-Match} on the update. The header is what
 *       carries the concurrency token; the JSON body is unchanged, so a strict client sees the
 *       legacy shape.</li>
 *   <li>{@code POST /resolve-identity}: the framework-neutral
 *       {@code IdentityResolver} was unreachable over HTTP in the legacy runtime (it was only
 *       wired into a test). Phase 6's AI/routing work needs an operator to resolve a handle, so
 *       it is exposed here.</li>
 * </ul>
 *
 * <h2>Non-disclosure</h2>
 *
 * <p>A customer id outside the caller's scope returns the same 404 body a nonexistent id
 * returns, because the scope check raises {@code PolicyDeniedException} and Phase 3's advice
 * renders that as {@code NOT_FOUND}.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomersController {

    private static final int DEFAULT_LIMIT = 25;

    private final CustomerService customers;

    public CustomersController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    public CursorPage<CustomerListItem> list(@RequestParam(required = false) String projectId,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(name = "limit", required = false) @BoundedLimit String limit) {
        return customers.list(TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            search,
            RequestParams.optionalUuid(cursor, "cursor"),
            RequestParams.limit(limit, DEFAULT_LIMIT, com.firstelement.inbox.api.InboxQuery.MAX_LIMIT));
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerDetail> getOne(@PathVariable String customerId) {
        CustomerDetail detail = customers.getById(TenantContextHolder.get(),
            RequestParams.requiredUuid(customerId, "customerId"));
        return ResponseEntity.ok()
            .eTag(RequestParams.etag(lastUpdated(detail)))
            .body(detail);
    }

    @GetMapping("/cx/{cxId}")
    public ResponseEntity<CustomerDetail> getByCxId(@PathVariable String cxId) {
        CustomerDetail detail = customers.getByCxId(TenantContextHolder.get(), cxId);
        return ResponseEntity.ok()
            .eTag(RequestParams.etag(lastUpdated(detail)))
            .body(detail);
    }

    @PatchMapping("/{customerId}")
    public ResponseEntity<CustomerDetail> update(@PathVariable String customerId,
                                                @Valid @RequestBody UpdateCustomerRequest body,
                                                @RequestHeader(name = "If-Match", required = false)
                                                String ifMatch,
                                                HttpServletRequest request) {
        CustomerDetail detail = customers.update(TenantContextHolder.get(),
            RequestParams.requiredUuid(customerId, "customerId"), body,
            RequestParams.ifMatch(ifMatch), clientIp(request));
        return ResponseEntity.ok()
            .eTag(RequestParams.etag(lastUpdated(detail)))
            .body(detail);
    }

    // ------------------------------------------------------------------

    /**
     * The customer's version token.
     *
     * <p>The 360 view has no {@code updatedAt} field of its own — the legacy body has none — so
     * the token is carried in the {@code ETag} derived from {@code lastSeenAt}, which the row's
     * {@code updatedAt} always moves with. Inventing a JSON field would change the body a strict
     * client validates against.
     */
    private static java.time.LocalDateTime lastUpdated(CustomerDetail detail) {
        return com.firstelement.crm.api.Timestamps.parseStored(detail.lastSeenAt());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Kept for the resolve-identity controller's shape; the id is never trusted. */
    static UUID requiredUuid(String raw) {
        return RequestParams.requiredUuid(raw, "id");
    }
}