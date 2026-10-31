package com.firstelement.crm.service;

import com.firstelement.crm.api.AppointmentSummary;
import com.firstelement.crm.api.CustomerConversationSummary;
import com.firstelement.crm.api.CustomerDetail;
import com.firstelement.crm.api.CustomerIdentityView;
import com.firstelement.crm.api.CustomerListItem;
import com.firstelement.crm.api.FollowUpSummary;
import com.firstelement.crm.api.Timestamps;
import com.firstelement.crm.api.UpdateCustomerRequest;
import com.firstelement.crm.audit.CrmAuditRecord;
import com.firstelement.crm.audit.CrmAuditWriter;
import com.firstelement.crm.domain.CustomerLocale;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.error.VersionConflictException;
import com.firstelement.crm.persistence.CustomerRepository;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Customers: the list, the 360 view, and the profile update.
 *
 * <h2>Behaviour ported from {@code apps/api/src/customers/customers.service.ts}</h2>
 *
 * <ul>
 *   <li>A supplied {@code projectId} narrows the scope; its absence means "every project I can
 *       see", never "every project".</li>
 *   <li>List ordering is {@code lastSeenAt desc, id desc}; search covers CX id, display name,
 *       primary email and primary phone.</li>
 *   <li>A row that is outside the caller's scope produces the same 404 as a row that does not
 *       exist, because {@code PolicyDeniedException} renders that way.</li>
 *   <li>An update re-reads the customer through its own project scope, writes, audits the
 *       <em>changed field names only</em>, and returns the full 360 view.</li>
 * </ul>
 *
 * <h2>Optimistic concurrency</h2>
 *
 * <p>{@code expectedUpdatedAt} comes from {@code If-Match}. When it is supplied the write is
 * conditional on it and a collision raises {@link VersionConflictException} (409) with the
 * current version. When it is absent the legacy last-writer-wins behaviour applies, so an
 * existing client that sends no {@code If-Match} is unaffected.
 *
 * <h2>Every query is scoped before it is built</h2>
 *
 * <p>{@code TenantQueryGuard} validates the requested project; {@link CrmQueryScope} carries the
 * predicate into the statement, including the accessible-project set for an unscoped request.
 * There is no code path here that reads a customer without both.
 *
 * <h2>The list reads a page in four statements</h2>
 *
 * <p>A list item carries a {@code latestTicket}, up to five follow-ups and up to five
 * appointments. Reading those per row made {@code GET /api/v1/customers?limit=50} issue
 * {@code 1 + 3 × 50 = 151} statements, all of them index-served and individually fast, and put
 * the request over the section-7 budget while the database was answering in a fraction of it.
 * {@link #loadPageProjection} reads all three for the whole page, once, under the same scope —
 * four statements for a page, and the number does not grow with {@code limit}.
 */
@Service
public class CustomerService {

    /** The legacy caps: five leads and five appointments in both projections. */
    private static final int LEGACY_CHILD_LIMIT = 5;
    /** The legacy detail cap on a customer's conversations. */
    private static final int CONVERSATION_LIMIT = 50;

    private final CustomerRepository customers;
    private final JsonCodec json;
    private final TenantQueryGuard guard;
    private final CrmAuditWriter audit;

    public CustomerService(CustomerRepository customers,
                           JsonCodec json,
                           TenantQueryGuard guard,
                           CrmAuditWriter audit) {
        this.customers = customers;
        this.json = json;
        this.guard = guard;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    @RequirePermission(Permission.CUSTOMER_READ)
    public CursorPage<CustomerListItem> list(TenantContext context,
                                            UUID requestedProjectId,
                                            String search,
                                            UUID cursor,
                                            int limit) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, requestedProjectId);

        CustomerRepository.CursorBoundary boundary = null;
        if (cursor != null) {
            boundary = customers.cursorBoundary(scope, cursor)
                .orElseThrow(() -> ValidationFailedException.code("INVALID_CURSOR",
                    "cursor does not identify a customer in scope", Map.of()));
        }

        List<CustomerRepository.CustomerRow> rows =
            customers.list(scope, search, boundary, limit + 1);

        CursorPage<CustomerRepository.CustomerRow> page =
            CursorPage.of(rows, limit, row -> row.id().toString());

        // The page's three child projections are read ONCE, before any row is projected.
        // Reading them inside the map() below is what made a 50-row page cost 151 statements
        // instead of 4, and it is invisible from the SQL: every one of those 151 was a
        // correct, index-served answer to a question the database could have answered once.
        ListProjection projection = loadPageProjection(scope, page.items());

        List<CustomerListItem> items = page.items().stream()
            .map(row -> toListItem(row, projection))
            .toList();
        return new CursorPage<>(items, page.nextCursor());
    }

    /**
     * The newest conversation, follow-ups and appointments for every customer on a page.
     *
     * <p>Three statements for the whole page, scoped by the same {@link CrmQueryScope} the page
     * itself was read with. An empty page reads nothing at all: each batched method returns an
     * empty map for an empty id list rather than binding an {@code in ()}, which PostgreSQL
     * rejects as a syntax error — a 500 on the one request that most needs a quiet, empty
     * answer.
     */
    private ListProjection loadPageProjection(CrmQueryScope scope,
                                              List<CustomerRepository.CustomerRow> page) {
        if (page.isEmpty()) {
            return ListProjection.EMPTY;
        }
        List<UUID> ids = page.stream().map(CustomerRepository.CustomerRow::id).toList();
        return new ListProjection(
            customers.latestTickets(scope, ids),
            customers.leadsFor(scope, ids, LEGACY_CHILD_LIMIT),
            customers.appointmentsFor(scope, ids, LEGACY_CHILD_LIMIT));
    }

    /**
     * The three per-customer child projections a whole page needs.
     *
     * <p>A customer absent from a map has no row of that kind — the same answer the per-row
     * query gave as an empty {@link Optional}, rendered as a {@code null}
     * {@code latestTicket} and an empty list.
     */
    private record ListProjection(
        Map<UUID, CustomerRepository.LatestTicketRow> latestTickets,
        Map<UUID, List<CustomerRepository.LeadRow>> followUps,
        Map<UUID, List<CustomerRepository.AppointmentRow>> appointments) {

        private static final ListProjection EMPTY =
            new ListProjection(Map.of(), Map.of(), Map.of());
    }

    // ------------------------------------------------------------------
    // single customer
    // ------------------------------------------------------------------

    @RequirePermission(Permission.CUSTOMER_READ)
    public CustomerDetail getById(TenantContext context, UUID customerId) {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(context);
        return toDetail(scope, requireCustomer(scope, customerId));
    }

    @RequirePermission(Permission.CUSTOMER_READ)
    public CustomerDetail getByCxId(TenantContext context, String cxId) {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(context);
        CustomerRepository.CustomerRow row = customers.findByCxIdInScope(scope, cxId)
            .orElseThrow(() -> new ResourceNotFoundException("Customer"));
        return toDetail(scope, row);
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    /**
     * Updates the customer profile.
     *
     * <p>Legacy semantics preserved: the read happens through the caller's accessible scope,
     * the write is pinned to that row's own project (so a membership change between the two
     * cannot widen it), the audit row records changed field names only, and the response is the
     * full customer view.
     */
    @RequirePermission(Permission.CUSTOMER_UPDATE)
    public CustomerDetail update(TenantContext context,
                                 UUID customerId,
                                 UpdateCustomerRequest request,
                                 LocalDateTime expectedUpdatedAt,
                                 String ipAddress) {
        CrmQueryScope readable = CrmQueryScope.accessibleProjects(context);
        CustomerRepository.CustomerRow existing = requireCustomer(readable, customerId);

        // Pin the write to the row's own project and re-assert the caller's membership on it,
        // exactly as the legacy projectScope(ctx, existing.projectId) did.
        CrmQueryScope writable = CrmQueryScope.forProject(guard, context, existing.projectId());

        List<String> assignments = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (request != null) {
            validate(request);
            if (request.displayName() != null) {
                assignments.add("\"displayName\" = :displayName");
                parameters.put("displayName", request.displayName().trim());
            }
            if (request.primaryEmail() != null) {
                assignments.add("\"primaryEmail\" = :primaryEmail");
                // The legacy schema trimmed and lower-cased before storing.
                parameters.put("primaryEmail",
                    request.primaryEmail().trim().toLowerCase(Locale.ROOT));
            }
            if (request.primaryPhone() != null) {
                assignments.add("\"primaryPhone\" = :primaryPhone");
                parameters.put("primaryPhone", request.primaryPhone().trim());
            }
            if (request.locale() != null) {
                assignments.add("\"locale\" = cast(:locale as \"Locale\")");
                parameters.put("locale", request.locale());
            }
            if (request.notes() != null) {
                assignments.add("\"notes\" = :notes");
                parameters.put("notes", request.notes().trim());
            }
            if (request.tags() != null) {
                assignments.add("\"tags\" = cast(:tags as text[])");
                parameters.put("tags", request.tags().stream().map(String::trim).toArray(String[]::new));
            }
            if (request.metadata() != null) {
                assignments.add("\"metadata\" = CAST(:metadata AS jsonb)");
                parameters.put("metadata", json.write(request.metadata()));
            }
        }

        if (!assignments.isEmpty()) {
            int changed = customers.update(writable, customerId, expectedUpdatedAt, assignments,
                parameters, LocalDateTime.now());
            if (changed == 0) {
                CustomerRepository.CustomerRow current = customers.findInScope(writable, customerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer"));
                throw new VersionConflictException("Customer", Timestamps.toIso(expectedUpdatedAt),
                    Timestamps.toIso(current.updatedAt()));
            }
        }

        CustomerRepository.CustomerRow updated = requireCustomer(writable, customerId);

        audit.record(CrmAuditRecord.of(context.organizationId(), updated.projectId(), context.userId(),
            CrmAuditRecord.CUSTOMER_DATA_UPDATED, "Customer", customerId,
            // Field names only — customer data does not belong in the audit log.
            Map.of("changedFields", List.copyOf(changedFieldNames(assignments))), ipAddress));

        return toDetail(writable, updated);
    }

    // ------------------------------------------------------------------
    // projections
    // ------------------------------------------------------------------

    CustomerRepository.CustomerRow requireCustomer(CrmQueryScope scope, UUID customerId) {
        return customers.findInScope(scope, customerId)
            .orElseThrow(() -> new ResourceNotFoundException("Customer"));
    }

    CustomerListItem toListItem(CustomerRepository.CustomerRow row, ListProjection projection) {
        CustomerRepository.LatestTicketRow ticket = projection.latestTickets().get(row.id());
        return new CustomerListItem(
            row.id(),
            row.projectId(),
            row.projectName(),
            row.cxId(),
            row.displayName(),
            row.primaryEmail(),
            row.primaryPhone(),
            row.locale(),
            row.tags(),
            json.readMap(row.metadata()),
            row.conversationCount(),
            Timestamps.toIso(row.lastSeenAt()),
            Timestamps.toIso(row.createdAt()),
            ticket == null ? null : new CustomerListItem.LatestTicket(
                ticket.id(),
                ticket.ticketNumber(),
                Timestamps.toIso(ticket.createdAt()),
                ticket.status(),
                ticket.subject()),
            followUps(projection.followUps().getOrDefault(row.id(), List.of())),
            appointments(projection.appointments().getOrDefault(row.id(), List.of())).stream()
                .map(AppointmentSummary::asListItem).toList());
    }

    CustomerDetail toDetail(CrmQueryScope scope, CustomerRepository.CustomerRow row) {
        return new CustomerDetail(
            row.id(),
            row.projectId(),
            row.cxId(),
            row.displayName(),
            row.primaryEmail(),
            row.primaryPhone(),
            row.locale(),
            row.tags(),
            row.notes(),
            json.readMap(row.metadata()),
            Timestamps.toIso(row.firstSeenAt()),
            Timestamps.toIso(row.lastSeenAt()),
            new CustomerDetail.ProjectRef(row.projectName()),
            customers.identities(scope, row.id()).stream()
                .map(identity -> new CustomerIdentityView(
                    identity.id(),
                    identity.platform(),
                    identity.externalId(),
                    identity.displayName(),
                    identity.isVerified(),
                    Timestamps.toIso(identity.createdAt())))
                .toList(),
            customers.conversations(scope, row.id(), CONVERSATION_LIMIT).stream()
                .map(conversation -> new CustomerConversationSummary(
                    conversation.id(),
                    conversation.ticketNumber(),
                    conversation.channel(),
                    conversation.status(),
                    conversation.mode(),
                    conversation.priority(),
                    conversation.subject(),
                    conversation.messageCount(),
                    Timestamps.toIso(conversation.lastMessageAt()),
                    Timestamps.toIso(conversation.createdAt()),
                    conversation.assigneeFullName()))
                .toList(),
            leads(scope, row.id(), 0),
            appointments(scope, row.id(), 0),
            row.projectName());
    }

    private List<FollowUpSummary> leads(CrmQueryScope scope, UUID customerId, int limit) {
        return followUps(customers.leads(scope, customerId, limit));
    }

    /** Projects already-read lead rows. The list path batches these; the 360 reads one customer. */
    private List<FollowUpSummary> followUps(List<CustomerRepository.LeadRow> rows) {
        return rows.stream()
            .map(lead -> new FollowUpSummary(
                lead.id(),
                lead.name(),
                lead.status(),
                lead.priority(),
                lead.ticketNumber(),
                Timestamps.toIso(lead.ticketDate()),
                Timestamps.toIso(lead.followUpDate()),
                Timestamps.toIso(lead.createdAt())))
            .toList();
    }

    private List<AppointmentSummary> appointments(CrmQueryScope scope, UUID customerId, int limit) {
        return appointments(customers.appointments(scope, customerId, limit));
    }

    /** Projects already-read appointment rows. */
    private List<AppointmentSummary> appointments(List<CustomerRepository.AppointmentRow> rows) {
        return rows.stream()
            .map(appointment -> new AppointmentSummary(
                appointment.id(),
                appointment.title(),
                Timestamps.toIso(appointment.appointmentDate()),
                appointment.durationMinutes(),
                appointment.status(),
                appointment.service(),
                appointment.doctorName(),
                appointment.notes(),
                appointment.ticketNumber(),
                Timestamps.toIso(appointment.ticketDate()),
                Timestamps.toIso(appointment.createdAt())))
            .toList();
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    /** The legacy zod rules, enforced at the edge so nothing invalid reaches the row. */
    void validate(UpdateCustomerRequest request) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (request.displayName() != null) {
            String name = request.displayName().trim();
            if (name.isEmpty() || name.length() > UpdateCustomerRequest.MAX_NAME) {
                problems.put("displayName",
                    "must be between 1 and " + UpdateCustomerRequest.MAX_NAME + " characters");
            }
        }
        if (request.primaryEmail() != null) {
            String email = request.primaryEmail().trim().toLowerCase(Locale.ROOT);
            if (email.length() > UpdateCustomerRequest.MAX_EMAIL || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                problems.put("primaryEmail", "must be a valid email address");
            }
        }
        if (request.primaryPhone() != null) {
            String phone = request.primaryPhone().trim();
            if (!phone.matches(UpdateCustomerRequest.PHONE_PATTERN)) {
                problems.put("primaryPhone", "must be an international phone number");
            }
        }
        if (request.locale() != null && !isSupportedLocale(request.locale())) {
            problems.put("locale", "must be ar or en");
        }
        if (request.notes() != null && request.notes().trim().length() > UpdateCustomerRequest.MAX_NOTES) {
            problems.put("notes", "must be at most " + UpdateCustomerRequest.MAX_NOTES + " characters");
        }
        if (request.tags() != null) {
            if (request.tags().size() > UpdateCustomerRequest.MAX_TAGS) {
                problems.put("tags", "must be at most " + UpdateCustomerRequest.MAX_TAGS + " entries");
            }
            for (String tag : request.tags()) {
                String trimmed = tag == null ? "" : tag.trim();
                if (trimmed.isEmpty() || trimmed.length() > UpdateCustomerRequest.MAX_TAG_LENGTH) {
                    problems.put("tags", "each tag must be 1.."
                        + UpdateCustomerRequest.MAX_TAG_LENGTH + " characters");
                    break;
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationFailedException("Request validation failed",
                Map.of("fields", problems));
        }
    }

    private static boolean isSupportedLocale(String locale) {
        try {
            CustomerLocale.fromValue(locale);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<String> changedFieldNames(Iterable<String> assignments) {
        List<String> names = new ArrayList<>();
        for (String assignment : assignments) {
            int dot = assignment.indexOf('.');
            int space = assignment.indexOf(' ');
            int end = assignment.length();
            if (dot > 0) {
                end = Math.min(end, dot);
            }
            if (space > 0) {
                end = Math.min(end, space);
            }
            names.add(assignment.substring(0, end).replace("\"", ""));
        }
        return names;
    }
}