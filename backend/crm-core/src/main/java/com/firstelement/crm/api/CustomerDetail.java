package com.firstelement.crm.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The customer 360 view.
 *
 * <h2>The field set is the legacy one, exactly</h2>
 *
 * <p>{@code CustomersService.getById} returned the Prisma selection spread into an object literal
 * and then added a few overrides. Three consequences are reproduced verbatim, because a client
 * validating the response would reject a "cleaned up" version:
 *
 * <ul>
 *   <li>The key is {@code leads}, not {@code followUps}. The customer <em>list</em> maps the same
 *       rows to {@code followUps}; the 360 view never did.</li>
 *   <li>{@code project} is present <em>and</em> {@code projectName} is present, because the spread
 *       kept the joined {@code project.name} and the literal added the flattened name.</li>
 *   <li>There is no {@code organizationId} and no {@code createdAt}: the legacy selection did not
 *       select them.</li>
 * </ul>
 */
public record CustomerDetail(
    UUID id,
    UUID projectId,
    String cxId,
    String displayName,
    String primaryEmail,
    String primaryPhone,
    String locale,
    List<String> tags,
    String notes,
    Map<String, Object> metadata,
    String firstSeenAt,
    String lastSeenAt,
    ProjectRef project,
    List<CustomerIdentityView> identities,
    List<CustomerConversationSummary> conversations,
    List<FollowUpSummary> leads,
    List<AppointmentSummary> appointments,
    String projectName
) {

    /** The joined {@code project.name} the legacy spread left in the body. */
    public record ProjectRef(String name) {
    }
}